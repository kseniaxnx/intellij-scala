package org.jetbrains.plugins.scala.failed.resolve

/**
  * @author Roman.Shein
  * @since 31.03.2016.
  */
class TypeAliasConstructorTest extends FailedResolveTest("typeAlias") {
  def testSCL13742(): Unit = doTest()
}
