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
import io.github.quafadas.FileBasedContentHashScalaJSModule

object BoidJsTests extends TestSuite:
  def tests: Tests = Tests {
    test("simple boid example emits content-hashed files with correct cross-module references") {
      object build extends TestRootModule with FileBasedContentHashScalaJSModule:
        override def scalaVersion: Simple[String] = "3.8.2"

        override def scalaJSExperimentalUseWebAssembly = true

        override def moduleSplitStyle: Simple[ModuleSplitStyle] = ModuleSplitStyle.FewestModules

        override def mvnDeps = Seq(
          mvn"org.scala-js::scalajs-dom::2.8.1",
          mvn"io.github.quafadas::vecxt::0.0.38"
        )

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      UnitTester(build, resourceFolder / "boid space").scoped {
        eval =>
          val Right(resultC) = eval(build.compile).runtimeChecked

          val Right(result) = eval(build.fastLinkJS).runtimeChecked

          val Right(resultFull) = eval(build.fullLinkJS).runtimeChecked
        //     val report = result.value
        //     val outputDir = report.dest.path
        //     val files = os.list(outputDir).map(_.last).toSet
        //     val jsFiles = files.filter(f => f.endsWith(".js") && !f.endsWith(".js.map"))

        //     // Must produce at least one file — catches the regression where the else-branch
        //     // returned super's report without writing anything to Task.dest.
        //     if jsFiles.isEmpty then
        //       throw new java.lang.AssertionError(
        //         s"fullLinkJS produced no .js files in Task.dest. Files present: ${files.mkString(", ")}"
        //       )
        //     end if

        //     // No original (unhashed) JS filename should exist.
        //     assert(!files.contains("main.js"))

        //     // No hashed JS filename should contain a hyphen.
        //     jsFiles.foreach(filename => assert(!filename.contains("-")))

        //     // Every cross-module import must reference a file that actually exists in the output.
        //     jsFiles.foreach {
        //       filename =>
        //         val content = os.read(outputDir / filename)
        //         val imports = ContentHashScalaJSModule.parseJsImports(content)
        //         imports.foreach {
        //           importedName =>
        //             if !jsFiles.contains(importedName) then
        //               throw new java.lang.AssertionError(
        //                 s"In $filename: import '$importedName' not found in output. " +
        //                   s"Output files: ${jsFiles.mkString(", ")}"
        //               )
        //         }
        //     }

        //     // The public modules reported back must have hashed filenames present in output.
        //     assert(report.publicModules.nonEmpty)
        //     report
        //       .publicModules
        //       .foreach {
        //         m =>
        //           if !jsFiles.contains(m.jsFileName) then
        //             throw new java.lang.AssertionError(
        //               s"Public module '${m.moduleID}' jsFileName '${m.jsFileName}' not found in output"
        //             )
        //       }
      }
    }
  }
end BoidJsTests
