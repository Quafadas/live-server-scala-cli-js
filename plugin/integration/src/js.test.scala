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
import io.github.quafadas.FileBasedContentHashScalaJSModule

object SiteJsTests extends TestSuite:
  def tests: Tests = Tests {
    test("Hashed JS files have correct cross-module references") {
      object build extends TestRootModule with FileBasedContentHashScalaJSModule:
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

          // Every cross-module import inside each JS file must reference a file
          // that actually exists in the output directory (i.e. imports use hashed names).
          // This also verifies hash cascading: if a dependency's hash changed but the
          // importer's import reference was NOT rewritten, the imported name would not
          // exist in the output and this assertion would fail.
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

          val Right(mini) = eval(build.minified).runtimeChecked
          val miniDir = mini.value.dest.path
          val miniFiles = os.list(miniDir).map(_.last).toSet
          val miniJsFiles = miniFiles.filter(f => f.endsWith(".js") && !f.endsWith(".js.map"))
          val miniMapFiles = miniFiles.filter(_.endsWith(".js.map"))

          // 3.1: Every minified .js file has a corresponding .js.map
          miniJsFiles.foreach {
            jsFile =>
              val expectedMap = jsFile + ".map"
              if !miniMapFiles.contains(expectedMap) then
                throw new java.lang.AssertionError(
                  s"Minified $jsFile has no corresponding source map. Maps: ${miniMapFiles.mkString(", ")}"
                )
              end if
          }

          // 3.2: Minified hashes differ from fullLinkJS hashes
          // (post-minification content is different, so hashes must differ)
          miniJsFiles.foreach {
            miniName =>
              if jsFiles.contains(miniName) then
                throw new java.lang.AssertionError(
                  s"Minified filename '$miniName' is identical to fullLinkJS output — hash should differ"
                )
          }

          // 3.3: sourceMappingURL in each minified JS points to a .map file that exists in output
          miniJsFiles.foreach {
            jsFile =>
              val content = os.read(miniDir / jsFile)
              val urlPattern = """//# sourceMappingURL=(.+)""".r
              urlPattern.findFirstMatchIn(content) match
                case Some(m) =>
                  val mapRef = m.group(1).trim
                  if !miniMapFiles.contains(mapRef) then
                    throw new java.lang.AssertionError(
                      s"In $jsFile: sourceMappingURL=$mapRef but file not found. Maps: ${miniMapFiles.mkString(", ")}"
                    )
                  end if
                case None =>
                  throw new java.lang.AssertionError(
                    s"Minified $jsFile has no sourceMappingURL comment"
                  )
              end match
          }

      }
    }
  }
end SiteJsTests
