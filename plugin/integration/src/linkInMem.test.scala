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

object MemJsTests extends TestSuite:
  def tests: Tests = Tests {
    test("Hashed JS files have correct cross-module references") {
      object build extends TestRootModule with InMemoryFastLinkHashScalaJSModule:
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

    test("InMemoryHashScalaJSModule fullLinkJS with scalaJSMinify=true produces smaller JS than fastLinkJS") {
      object build extends TestRootModule with InMemoryFastLinkHashScalaJSModule:
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
          val Right(fastResult) = eval(build.fastLinkJS).runtimeChecked
          val fastDir = fastResult.value.dest.path
          val fastTotalSize = os.list(fastDir).filter(p => os.isFile(p) && p.ext == "js").map(os.size).sum

          val Right(fullResult) = eval(build.fullLinkJS).runtimeChecked
          val fullDir = fullResult.value.dest.path
          val fullTotalSize = os.list(fullDir).filter(p => os.isFile(p) && p.ext == "js").map(os.size).sum

          // fullLinkJS runs both the full Scala.js linker and terser; output must be strictly smaller.
          if fullTotalSize >= fastTotalSize then
            throw new java.lang.AssertionError(
              s"fullLinkJS+terser total JS size ($fullTotalSize) should be < fastLinkJS total JS size ($fastTotalSize)"
            )
          end if

          // All public modules must reference files that exist in Task.dest.
          assert(fullResult.value.publicModules.nonEmpty)
          fullResult
            .value
            .publicModules
            .foreach {
              m =>
                if !os.exists(fullDir / m.jsFileName) then
                  throw new java.lang.AssertionError(
                    s"Public module '${m.moduleID}' jsFileName '${m.jsFileName}' not found in fullLinkJS output"
                  )
            }
      }
    }

    test("InMemoryHashScalaJSModule fullLinkJS with scalaJSMinify=false writes hashed files without terser") {
      object build extends TestRootModule with InMemoryFastLinkHashScalaJSModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")
        override def scalaJSMinify = false

        override def mvnDeps = Seq(
          mvn"com.raquo::laminar::17.0.0"
        )

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      UnitTester(build, resourceFolder / "simple").scoped {
        eval =>
          val Right(result) = eval(build.fullLinkJS).runtimeChecked
          val outputDir = result.value.dest.path

          // Files must exist on disk.
          assert(os.exists(outputDir))
          val jsFiles = os.list(outputDir).filter(p => os.isFile(p) && p.ext == "js").map(_.last).toSet
          if jsFiles.isEmpty then
            throw new java.lang.AssertionError("Expected hashed JS files in fullLinkJS output, got none")
          end if

          // No unhashed names: each file must have a content hash segment (name has >= 2 dot-separated parts).
          jsFiles.foreach {
            name =>
              val parts = name.stripSuffix(".js").split('.')
              if parts.length < 2 then
                throw new java.lang.AssertionError(
                  s"Expected hashed filename like 'main.<hash>.js', got: $name"
                )
              end if
          }

          // No hyphens in filenames.
          jsFiles.foreach {
            name =>
              if name.contains("-") then
                throw new java.lang.AssertionError(s"Filename should not contain hyphens: $name")
              end if
          }

          // Public modules reference files that exist.
          assert(result.value.publicModules.nonEmpty)
          result
            .value
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
