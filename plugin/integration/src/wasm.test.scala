package io.github.quafadas.millSite

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import mill.scalajslib.ContentHashScalaJSModule
import utest.*

object SiteWasmTests extends TestSuite:
  def tests: Tests = Tests {
    test("WASM output has a hashed JS file with wasm binary preserved") {
      object build extends TestRootModule with mill.scalajslib.ContentHashScalaJSModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def scalaJSVersion: Simple[String] = "1.20.1"
        override def scalaJSExperimentalUseWebAssembly = true

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

          // The main entry-point JS file must be renamed to a hashed filename.
          assert(!files.contains("main.js"))
          val hashedMainJs = files.find(f => f.startsWith("main.") && f.endsWith(".js"))
          if hashedMainJs.isEmpty then throw new java.lang.AssertionError(s"no hashed main.js found in: $files")
          end if

          // The WASM binary must be preserved (not renamed).
          if !files.exists(_.endsWith(".wasm")) then
            throw new java.lang.AssertionError(s"no .wasm file found in: $files")
          end if

          // The Report's public module must reference the hashed JS file.
          assert(report.publicModules.nonEmpty)
          val reported = report.publicModules.head.jsFileName
          if reported != hashedMainJs.get then
            throw new java.lang.AssertionError(
              s"report module jsFileName should be ${hashedMainJs.get}, was $reported"
            )
          end if

      }
    }
  }
end SiteWasmTests
