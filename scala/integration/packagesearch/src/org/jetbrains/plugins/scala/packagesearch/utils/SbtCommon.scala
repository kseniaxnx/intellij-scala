package org.jetbrains.plugins.scala.packagesearch.utils

import com.jetbrains.packagesearch.intellij.plugin.extensibility.BuildSystemType
import org.jetbrains.plugins.scala.packagesearch.PackageSearchSbtBundle

object SbtCommon {
  val buildSystemType = new BuildSystemType(
    PackageSearchSbtBundle.message("packagesearch.sbt.build.system.name"),
    PackageSearchSbtBundle.message("packagesearch.sbt.build.system.key"))
  val libScopes = "Compile,Test"
  val defaultLibScope = "Compile"
  val scopeTerminology = "Configuration"
}
