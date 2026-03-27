package io.github.quafadas.sjsls

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import mill.scalajslib.ContentHashScalaJSModule
import mill.scalajslib.api.ModuleSplitStyle
import utest.*

object IndexHtmlTests extends TestSuite:
  def tests: Tests = Tests {
    test("Hashed JS files have correct cross-module references") {
      object build extends TestRootModule with io.github.quafadas.ScalaJsRefreshModule:
        override def scalaVersion: Simple[String] = "3.8.2"

        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")

        override def mvnDeps = Seq(
          mvn"com.raquo::laminar::17.0.0"
        )

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      UnitTester(build, resourceFolder / "simple").scoped {
        eval =>

          val Right(result) = eval(build.indexHtml).runtimeChecked
          val report = result.value
          val html = os.read(report.path)
          assert(html.contains("main.js"))

      }
    }
  }
end IndexHtmlTests
