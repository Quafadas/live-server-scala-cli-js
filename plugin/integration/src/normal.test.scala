package io.github.quafadas.millSite

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.ModuleSplitStyle
import utest.*

object NormalTests extends TestSuite:
  def tests: Tests = Tests {
    test("Normal ScalaJs Linking") {
      object build extends TestRootModule with ScalaJSModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def scalaJSVersion: Simple[String] = "1.20.2"
        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")

        override def moduleKind = mill.scalajslib.api.ModuleKind.ESModule

        override def mvnDeps = Seq(
          mvn"com.raquo::laminar::17.0.0"
        )

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      UnitTester(build, resourceFolder / "simple").scoped {
        eval =>

          val Right(result) = eval(build.fastLinkJS).runtimeChecked
          val report = result.value
          val outputDir = report.dest.path
          val files = os.list(outputDir).map(_.last).toSet
          files.foreach(println)
      }
    }
  }
end NormalTests