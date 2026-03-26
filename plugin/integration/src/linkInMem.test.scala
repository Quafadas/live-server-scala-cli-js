package io.github.quafadas.millSite

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import mill.scalajslib.ContentHashScalaJSModule
import mill.scalajslib.api.ModuleSplitStyle
import utest.*

object MemJsTests extends TestSuite:
  def tests: Tests = Tests {
    test("Hashed JS files have correct cross-module references") {
      object build extends TestRootModule with io.github.quafadas.InMemoryHashScalaJSModule:
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

          val Right(result) = eval(build.fastLinkJS).runtimeChecked
          val report = result.value
          val outputDir = report.dest.path
          val files = os.list(outputDir).map(_.last).toSet
          val jsFiles = files.filter(f => f.endsWith(".js") && !f.endsWith(".js.map"))

          // No original (unhashed) JS filename should exist.
          assert(!files.contains("main.js"))

          // No hashed JS filename should contain a hyphen (all "-" must be replaced with "_").
          jsFiles.foreach(filename => assert(!filename.contains("-")))

          // Every cross-module import must reference a file that actually exists in the output directory.
          jsFiles.foreach {
            filename =>
              val content = os.read(outputDir / filename)
              val imports = ContentHashScalaJSModule.parseJsImports(content)
              imports.foreach {
                importedName =>
                  if !jsFiles.contains(importedName) then
                    throw new java.lang.AssertionError(
                      s"In $filename: import '$importedName' not found in output. " +
                        s"Output files: ${jsFiles.mkString(", ")}"
                    )
              }
          }

          // The public module reported back must have a hashed filename present in output.
          assert(report.publicModules.nonEmpty)
          report
            .publicModules
            .foreach {
              m =>
                if !jsFiles.contains(m.jsFileName) then
                  throw new java.lang.AssertionError(
                    s"Public module '${m.moduleID}' jsFileName '${m.jsFileName}' not found in output"
                  )
            }

      }
    }
  }
end MemJsTests
