package org.jetbrains.plugins.scala.compilationCharts.ui

import java.awt.geom.{Point2D, Rectangle2D}
import java.awt.{Dimension, Graphics, Graphics2D, Point, RenderingHints}

import com.intellij.ide.ui.laf.UIThemeBasedLookAndFeelInfo
import com.intellij.ide.ui.{LafManager, UISettings}
import com.intellij.openapi.project.Project
import com.intellij.ui.components.{JBPanelWithEmptyText, JBScrollPane}
import com.intellij.util.ui.StartupUiUtil
import org.jetbrains.plugins.scala.compilationCharts.ui.Common._
import org.jetbrains.plugins.scala.compilationCharts.{CompilationProgressStateManager, CompileServerMetricsStateManager, Memory}
import org.jetbrains.plugins.scala.extensions.{ObjectExt, invokeLater}

import scala.concurrent.duration.{Duration, FiniteDuration}

class DiagramsComponent(chartsComponent: CompilationChartsComponent,
                        project: Project,
                        defaultZoom: Zoom)
  extends JBPanelWithEmptyText {

  import DiagramsComponent._

  // injecting due to cyclic dependency between scroll pane and diagrams component
  var scrollComponent: JBScrollPane = _

  private var initialized = false

  private var diagrams: Diagrams = _
  private var staticHeights: DiagramStaticHeights = _
  private var currentZoom = defaultZoom
  private var currentZoomPixels: Double = -1

  def setZoom(zoom: Zoom): Unit = {
    if (scrollComponent == null)
      return

    val viewport = scrollComponent.getViewport
    val viewRect = viewport.getViewRect
    val centerDuration = currentZoom.fromPixels(viewRect.getX + viewRect.getWidth / 2)

    currentZoom = zoom

    val newViewX = (currentZoom.toPixels(centerDuration) - viewRect.getWidth / 2).round.toInt
    val newViewPosition = new Point(newViewX, viewRect.y)

    updateZoomPixels()
    updatePreferredSize()

    viewport.setViewPosition(newViewPosition)
    chartsComponent.repaint()
  }

  def updateData(): Unit = {
    val progressState = CompilationProgressStateManager.get(project)
    val metricsState = CompileServerMetricsStateManager.get(project)
    diagrams = Diagrams.calculate(progressState, metricsState)
    staticHeights = getDiagramStaticHeights(diagrams.progressDiagram.rowCount)

    updateZoomPixels()
    updatePreferredSize()
    revalidate()

    initialized = true
  }

  private def updateZoomPixels(): Unit = {
    currentZoomPixels = currentZoom.toPixels(diagrams.progressTime + durationAhead)
  }

  /**
   * @note Don't depend on clip bounds when calculating preferred width and height!<br>
   * It can cause scroll bar flickers when resizing component using line between build tree view and compiler charts
   * or when resizing build tool window.<br>
   * If preferredWidth < clipBounds.width or preferredHeight < clipBounds.height,
   * layout manager will automatically stretch it to the maximum value, don't do it manually.
   */
  private def updatePreferredSize(): Unit = {
    val preferredSize: Dimension = {
      val preferredWidth = currentZoomPixels
      val preferredHeight = staticHeights.durationAxis + staticHeights.memoryDiagram + staticHeights.progressDiagram
      val rectDouble = new Rectangle2D.Double(0, 0, preferredWidth, preferredHeight)
      rectDouble.getBounds.getSize
    }
    setPreferredSize(preferredSize)
  }

  override def paintComponent(g: Graphics): Unit = {
    val graphics = g.asInstanceOf[Graphics2D]
    if (!initialized)
      return

    UISettings.setupAntialiasing(graphics)

    val darkTheme = isDarkTheme

    val Diagrams(progressDiagram, memoryDiagram, progressTime) = diagrams
    val clipBounds = g.getClipBounds
    val estimatedPreferredWidth = math.max(
      currentZoomPixels,
      clipBounds.width
    )
    val clips = getDiagramClips(clipBounds, staticHeights)

    val diagramPrinters = Seq(
      new ProgressDiagramPrinter(clips.progressDiagram, progressDiagram, currentZoom, estimatedPreferredWidth, darkTheme),
      new MemoryDiagramPrinter(clips.memoryDiagram, memoryDiagram, currentZoom, progressTime, darkTheme)
    )

    diagramPrinters.foreach(_.printBackground(graphics))
    printDiagramVerticalLines(graphics, clips.allDiagramsClip, estimatedPreferredWidth, progressTime)

    val aliasingHintValueBefore = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    diagramPrinters.foreach(_.printDiagram(graphics))
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aliasingHintValueBefore)

    printDurationAxis(graphics, clips.durationAxis, estimatedPreferredWidth)
  }

  private def getDiagramStaticHeights(progressDiagramRowCount: Int): DiagramStaticHeights =
    DiagramStaticHeights(
      ProgressRowHeight * progressDiagramRowCount,
      MinMemoryDiagramHeight,
      DurationAxisHeight
    )

  private def getDiagramClips(clipBounds: Rectangle2D, heights: DiagramStaticHeights): DiagramClips = {
    def nextClip(height: Double, prevClip: Rectangle2D): Rectangle2D.Double =
      new Rectangle2D.Double(clipBounds.getX, prevClip.getY + prevClip.getHeight, clipBounds.getWidth, height)

    val progressDiagramClip = new Rectangle2D.Double(clipBounds.getX, 0, clipBounds.getWidth, heights.progressDiagram)
    val memoryDiagramY = progressDiagramClip.y + progressDiagramClip.height
    val memoryDiagramHeight = math.max(
      clipBounds.getHeight - memoryDiagramY - heights.durationAxis,
      heights.memoryDiagram
    )
    val memoryDiagramClip = nextClip(memoryDiagramHeight, progressDiagramClip)
    val durationAxisClip = nextClip(heights.durationAxis, memoryDiagramClip)
    DiagramClips(
      progressDiagram = progressDiagramClip,
      memoryDiagram = memoryDiagramClip,
      durationAxis = durationAxisClip
    )
  }

  private def printDurationAxis(graphics: Graphics2D, clip: Rectangle2D, preferredWidth: Double): Unit = {
    graphics.printRect(clip, diagramBackgroundColor)
    graphics.printBorder(clip, Side.North, LineColor, BorderStroke)
    durationXIterator(preferredWidth).zipWithIndex.foreach { case (x, i) =>
      val point = new Point2D.Double(x, clip.getY)
      if (i % currentZoom.durationLabelPeriod == 0) {
        if (i != 0) graphics.printVerticalLine(point, LongDashLength, LineColor, DashStroke)
        val text = " " + stringify(i * currentZoom.durationStep)
        val textClip = new Rectangle2D.Double(point.x, clip.getY, clip.getWidth, clip.getHeight)
        val rendering = graphics.getTextRendering(textClip, text, SmallFont, HAlign.Left, VAlign.Top)
        val fixedRendering = rendering.translate(rendering.x, rendering.y + rendering.rect.getHeight / 4)
        graphics.doInClip(fixedRendering.rect)(_.printText(fixedRendering, TextColor))
      } else {
        graphics.printVerticalLine(point, DashLength, LineColor, DashStroke)
      }
    }
  }

  private def printDiagramVerticalLines(graphics: Graphics2D,
                                        clip: Rectangle2D,
                                        preferredWidth: Double,
                                        progressTime: FiniteDuration): Unit = {
    durationXIterator(preferredWidth).zipWithIndex.foreach { case (x, i) =>
      if (i != 0 && i % currentZoom.durationLabelPeriod == 0) {
        val point = new Point2D.Double(x, clip.getY)
        graphics.printVerticalLine(point, clip.getHeight, LineColor, DashedStroke)
      }
    }
    val progressLinePoint = currentZoom.toPixels(progressTime)
    val linePoint = new Point2D.Double(progressLinePoint, clip.getY)
    graphics.printVerticalLine(linePoint, clip.getHeight, TextColor, ProgressLineStroke)
  }

  private def durationXIterator(preferredWidth: Double): Iterator[Double] = {
    val zero = currentZoom.toPixels(Duration.Zero)
    val step = currentZoom.toPixels(currentZoom.durationStep)
    Iterator.iterate(zero)(_ + step).takeWhile(_ <= preferredWidth)
  }

  private def durationAhead: FiniteDuration =
    currentZoom.durationStep * currentZoom.durationLabelPeriod
}

object DiagramsComponent {

  private final case class DiagramClips(progressDiagram: Rectangle2D,
                                        memoryDiagram: Rectangle2D,
                                        durationAxis: Rectangle2D) {
    def allDiagramsClip: Rectangle2D = new Rectangle2D.Double(
      progressDiagram.getX,
      progressDiagram.getY,
      progressDiagram.getWidth,
      memoryDiagram.getY + memoryDiagram.getHeight,
    )
  }

  /** Components heights, which doesn't depend on any graphics context */
  private final case class DiagramStaticHeights(progressDiagram: Double,
                                                memoryDiagram: Double,
                                                durationAxis: Double)

  private def isDarkTheme: Boolean =
    Option(LafManager.getInstance.getCurrentLookAndFeel)
      .flatMap(_.asOptionOf[UIThemeBasedLookAndFeelInfo])
      .exists(_.getTheme.isDark) || StartupUiUtil.isUnderDarcula

  def stringify(bytes: Memory, showMb: Boolean): String = {
    val megabytes = toMegabytes(bytes)
    val suffix = if (showMb) " MB" else ""
    s"$megabytes$suffix"
  }

  def smartRound(bytes: Memory): Memory =
    (toMegabytes(bytes).toDouble / 100).round * 100 * 1024 * 1024

  private def toMegabytes(bytes: Memory): Long =
    math.round(bytes.toDouble / 1024 / 1024)

  private def stringify(duration: FiniteDuration): String = {
    val minutes = duration.toMinutes
    val seconds = duration.toSeconds % 60
    val minutesStr = Option(minutes).filter(_ > 0).map(_.toString + "m")
    val secondsStr = Option(seconds).filter(_ > 0).map(_.toString + "s")
    val result = Seq(minutesStr, secondsStr).flatten.mkString(" ")
    if (result.nonEmpty) result else "0"
  }

  private final val MinMemoryDiagramHeight = ProgressRowHeight * 3
  private final val DurationAxisHeight = ProgressRowHeight * 0.75
  private final val LongDashLength = DashLength * 2

  private final val DashedStroke = new LineStroke(DashStroke.thickness, dashLength = Some((ProgressRowHeight / 5).toFloat))
}
