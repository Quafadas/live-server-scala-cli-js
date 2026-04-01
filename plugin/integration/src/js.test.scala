package io.github.quafadas.sjsls

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import mill.scalajslib.ContentHashScalaJSModule
import mill.scalajslib.api.ModuleSplitStyle
import mill.scalajslib.api.ESModuleImportMapping
import utest.*

object SiteJsTests extends TestSuite:
  def tests: Tests = Tests {
    test("fullLinkJS emits content-hashed files with correct cross-module references") {
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
          val Right(result) = eval(build.fullLinkJS).runtimeChecked
          val report = result.value
          val outputDir = report.dest.path
          val files = os.list(outputDir).map(_.last).toSet
          val jsFiles = files.filter(f => f.endsWith(".js") && !f.endsWith(".js.map"))

          // Must produce at least one file — catches the regression where the else-branch
          // returned super's report without writing anything to Task.dest.
          if jsFiles.isEmpty then
            throw new java.lang.AssertionError(
              s"fullLinkJS produced no .js files in Task.dest. Files present: ${files.mkString(", ")}"
            )
          end if

          // No original (unhashed) JS filename should exist.
          assert(!files.contains("main.js"))

          // No hashed JS filename should contain a hyphen.
          jsFiles.foreach(filename => assert(!filename.contains("-")))

          // Every cross-module import must reference a file that actually exists in the output.
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

          // The public modules reported back must have hashed filenames present in output.
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

    /** YAML friendly overrides
      */
    test("Import map and smallModulesFor populated from sub task") {
      object build extends TestRootModule with ScalaJsRefreshModule:
        override def scalaVersion: Simple[String] = "3.8.2"

        override def smallModulesFor = Seq("webapp")
        override def mvnDeps = Seq(
          mvn"com.raquo::laminar::17.0.0"
        )

        override def importMap = Map("@foo" -> "https://cdn.skypack.dev/foo/")

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      UnitTester(build, resourceFolder / "simple").scoped {
        eval =>
          val Right(result) = eval(build.scalaJSImportMap).runtimeChecked
          val report = result.value
          assert(report.nonEmpty)
          assert(report.exists {
            case ESModuleImportMapping.Prefix(prefix, target) =>
              prefix == "@foo" && target == "https://cdn.skypack.dev/foo/"
            case _ => throw new java.lang.AssertionError(s"Unexpected import mapping type: $report")
          })

          val Right(smallModulesResult) = eval(build.smallModulesFor).runtimeChecked
          val smallModules = smallModulesResult.value
          assert(smallModules.contains("webapp"))
      }

    }

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
      }
    }

    test("fullLinkJS terser-minifies JS output when scalaJSMinify is true") {
      object buildMinify extends TestRootModule with FileBasedContentHashScalaJSModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def scalaJSMinify: Simple[Boolean] = true
        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")
        override def mvnDeps = Seq(mvn"com.raquo::laminar::17.0.0")
        lazy val millDiscover = Discover[this.type]
      end buildMinify

      object buildNoMinify extends TestRootModule with FileBasedContentHashScalaJSModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def scalaJSMinify: Simple[Boolean] = false
        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")
        override def mvnDeps = Seq(mvn"com.raquo::laminar::17.0.0")
        lazy val millDiscover = Discover[this.type]
      end buildNoMinify

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      var noMinSize = 0L
      UnitTester(buildNoMinify, resourceFolder / "simple").scoped {
        evalNoMinify =>
          val Right(noMinResult) = evalNoMinify(buildNoMinify.fullLinkJS).runtimeChecked
          noMinSize = os.list(noMinResult.value.dest.path).filter(p => os.isFile(p) && p.ext == "js").map(os.size).sum
      }

      UnitTester(buildMinify, resourceFolder / "simple").scoped {
        evalMinify =>
          val Right(minResult) = evalMinify(buildMinify.fullLinkJS).runtimeChecked
          val minSize = os.list(minResult.value.dest.path).filter(p => os.isFile(p) && p.ext == "js").map(os.size).sum

          assert(
            minSize < noMinSize
          )
      }
    }
  }
end SiteJsTests
