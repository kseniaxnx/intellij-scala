package org.jetbrains.plugins.scala.failed.resolve

/**
  * @author Nikolay.Tropin
  */
class NamedArgumentTest extends FailedResolveTest("namedArgument") {

  def testSCL10487(): Unit = doTest() //apply method

  def testSCL13063(): Unit = doTest() //apply method

}
