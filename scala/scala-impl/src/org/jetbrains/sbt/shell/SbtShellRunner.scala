package org.jetbrains.sbt.shell

import java.util

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.console._
import com.intellij.execution.process.{ColoredProcessHandler, OSProcessHandler, ProcessHandler}
import com.intellij.execution.runners.AbstractConsoleRunnerWithHistory
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.execution.ui.{RunContentDescriptor, RunnerLayoutUi}
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem._
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.ui.content.{Content, ContentFactory}
import com.pty4j.unix.UnixPtyProcess
import com.pty4j.{PtyProcess, WinSize}
import javax.swing.{Icon, JLabel, SwingConstants}
import org.jetbrains.plugins.scala.extensions.{executeOnPooledThread, invokeLater}
import org.jetbrains.plugins.scala.icons.Icons
import org.jetbrains.plugins.scala.project.ProjectExt
import org.jetbrains.plugins.scala.statistics.{FeatureKey, Stats}
import org.jetbrains.sbt.shell.SbtShellRunner._

import scala.collection.JavaConverters._

final class SbtShellRunner(project: Project, consoleTitle: String, debugConnection: Option[RemoteConnection])
  extends AbstractConsoleRunnerWithHistory[SbtShellConsoleView](project, consoleTitle, project.baseDir.getCanonicalPath)
    with Disposable {

  private val log = Logger.getInstance(getClass)

  // lazy so that getProcessHandler will return something initialized when this is first accessed
  private lazy val myConsoleExecuteActionHandler: SbtShellExecuteActionHandler =
    new SbtShellExecuteActionHandler(getProcessHandler)

  // the process handler should only be used to listen to the running process!
  // SbtProcessManager is solely responsible for destroying/respawning
  private lazy val myProcessHandler: ColoredProcessHandler =
    SbtProcessManager.forProject(project)
      .acquireShellProcessHandler()

  // is called from AbstractConsoleRunnerWithHistory.initAndRun synchronously
  override def createProcess: Process = myProcessHandler.getProcess

  override def createProcessHandler(process: Process): OSProcessHandler = myProcessHandler

  //called manually by Scala Plugin, underlying initialization can be done asynchronously, so
  //right after the method execution `getConsoleView` can still return `null` and `isRunning` return false
  override def initAndRun(): Unit = {
    showInitializingPlaceholder()
    super.initAndRun()
  }

  private def showInitializingPlaceholder(): Unit = {
    SbtShellToolWindowFactory.instance(project).foreach { toolWindow =>
      invokeLater {
        val label = new JLabel(s"Initializing ${SbtShellToolWindowFactory.Title}...", SwingConstants.CENTER)
        label.setOpaque(true)
        toolWindow.setContent(new ContentImpl(label, "", false))
      }
    }
  }

  // is called from AbstractConsoleRunnerWithHistory.initAndRun from EDT, can be invoked asynchronously
  override def createConsoleView: SbtShellConsoleView = {
    val cv = SbtShellConsoleView(project, debugConnection)
    Disposer.register(this, cv)
    cv
  }

  // is called from AbstractConsoleRunnerWithHistory.initAndRun from EDT, can be invoked asynchronously
  override def createContentDescriptorAndActions(): Unit = if(notInTest) {
    super.createContentDescriptorAndActions()

    executeOnPooledThread {
      initSbtShell()
    }
  }

  private def initSbtShell(): Unit = {
    val consoleView = getConsoleView
    if (consoleView == null) {
      log.error("console view should be created in initAndRun by this moment")
      return
    }

    patchWindowSize(myProcessHandler.getProcess)

    consoleView.setPrompt("(initializing) >")

    myProcessHandler.addProcessListener(shellPromptChanger(consoleView))

    SbtShellCommunication.forProject(project).initCommunication(myProcessHandler)

    initSbtShellUi(consoleView)
  }

  // on Windows the terminal defaults to 80 columns which wraps and breaks highlighting.
  // Use a wider value that should be reasonable in most cases. Has no effect on Unix.
  // TODO perhaps determine actual width of window and adapt accordingly
  private def patchWindowSize(getProcess: Process): Unit = if (notInTest) {
    myProcessHandler.getProcess match {
      case _: UnixPtyProcess => // don't need to do stuff
      case proc: PtyProcess  => proc.setWinSize(new WinSize(2000, 100))
      case _                 =>
    }
  }

  // TODO update icon with ready/working state
  private def shellPromptChanger(consoleView: SbtShellConsoleView): SbtShellReadyListener = {
    def scrollToEnd(): Unit = invokeLater {
      val editor = consoleView.getHistoryViewer
      if (!editor.isDisposed)
        EditorUtil.scrollToTheEnd(editor)
    }

    new SbtShellReadyListener(
      whenReady = if (notInTest) {
        consoleView.setPrompt(">")
        scrollToEnd()
      },
      whenWorking = if (notInTest) {
        consoleView.setPrompt("(busy) >")
        scrollToEnd()
      }
    )
  }

  private def initSbtShellUi(consoleView: SbtShellConsoleView): Unit = if (notInTest) {
    SbtShellToolWindowFactory.instance(project).foreach { toolWindow =>
      invokeLater {
        val content = createToolWindowContent(consoleView)
        toolWindow.setContent(content)
      }
    }
  }

  private def createToolWindowContent(consoleView: SbtShellConsoleView): Content = {
    //Create runner UI layout
    val factory = RunnerLayoutUi.Factory.getInstance(project)
    val layoutUi = factory.create("sbt-shell-toolwindow-runner", "", "session", project)
    val layoutComponent = layoutUi.getComponent
    // Adding actions
    val group = new DefaultActionGroup
    layoutUi.getOptions.setLeftToolbar(group, ActionPlaces.UNKNOWN)
    val console = layoutUi.createContent(
      SbtShellToolWindowFactory.ID,
      consoleView.getComponent,
      "sbt-shell-toolwindow-console",
      null, null
    )

    consoleView.addConsoleActions(group)

    layoutUi.addContent(console, 0, PlaceInGrid.right, false)

    val content = ContentFactory.SERVICE.getInstance.createContent(layoutComponent, "sbt-shell-toolwindow-content", true)
    val toolWindowTitle = project.getName
    content.setTabName(toolWindowTitle)
    content.setDisplayName(toolWindowTitle)
    content.setToolwindowTitle(toolWindowTitle)

    content
  }


  override def createExecuteActionHandler(): SbtShellExecuteActionHandler = {
    val historyController = new ConsoleHistoryController(SbtShellRootType, null, getConsoleView)
    historyController.install()

    myConsoleExecuteActionHandler
  }

  override def fillToolBarActions(toolbarActions: DefaultActionGroup,
                                  defaultExecutor: Executor,
                                  contentDescriptor: RunContentDescriptor): util.List[AnAction] = {

    // the actual toolbar actions are created in SbtShellConsoleView because this is a hackjob
    // the exec action needs to be created here so it is registered. TODO refactor so we don't need this
    List(createConsoleExecAction(myConsoleExecuteActionHandler)).asJava
  }

  override def getConsoleIcon: Icon = Icons.SBT_SHELL

  override def showConsole(defaultExecutor: Executor, contentDescriptor: RunContentDescriptor): Unit =
    openShell(contentDescriptor.isAutoFocusContent)

  /** Shows ToolWindow on UI thread asynchronously */
  def openShell(focus: Boolean): Unit =
    invokeLater {
      SbtShellToolWindowFactory.instance(project).foreach { toolWindow =>
        toolWindow.activate(null, focus)
      }
    }

  def getDebugConnection: Option[RemoteConnection] = debugConnection

  def isRunning: Boolean = getConsoleView match {
    case null => false
    case view => view.isRunning
  }

  override def dispose(): Unit = {}

  object SbtShellRootType extends ConsoleRootType("sbt.shell", getConsoleTitle)

  class SbtShellExecuteActionHandler(processHandler: ProcessHandler)
    extends ProcessBackedConsoleExecuteActionHandler(processHandler, true) {

    // input is echoed to the process anyway
    setAddCurrentToHistory(false)

    override def execute(text: String, console: LanguageConsoleView): Unit = {
      Stats.trigger(FeatureKey.sbtShellCommand)
      Stats.trigger(isTestCommand(text), FeatureKey.sbtShellTestCommand)

      EditorUtil.scrollToTheEnd(console.getHistoryViewer)
      super.execute(text, console)
    }

    private def isTestCommand(line: String): Boolean = {
      val trimmed = line.trim
      trimmed == "test" || trimmed.startsWith("testOnly") || trimmed.startsWith("testQuick")
    }
  }
}

object SbtShellRunner {

  private def notInTest: Boolean = !ApplicationManager.getApplication.isUnitTestMode
}