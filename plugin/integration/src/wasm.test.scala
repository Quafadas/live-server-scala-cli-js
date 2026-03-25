package io.github.quafadas.millSite

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import io.github.quafadas.InMemoryHashScalaJSModule
import utest.*

object SiteWasmTests extends TestSuite:
  def tests: Tests = Tests {
    test("fastLinkJS WASM output has a hashed wasm file") {
      object build extends TestRootModule with InMemoryHashScalaJSModule:
        override def scalaVersion: Simple[String] = "3.8.2"
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

          // Files live in-memory, not on disk
          val memFiles = build.inMemoryOutputDirectory.fileNames().toSet

          // The main entry-point JS file must be renamed to a hashed filename.
          assert(!memFiles.contains("main.wasm"))
          val hashedMainJs = memFiles.find(f => f.startsWith("main.") && f.endsWith(".js"))
          if hashedMainJs.isEmpty then throw new java.lang.AssertionError(s"no hashed main.js found in: $memFiles")
          end if

          // The WASM binary must be preserved (not renamed).
          if !memFiles.exists(_.endsWith(".wasm")) then
            throw new java.lang.AssertionError(s"no .wasm file found in: $memFiles")
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

    // Minification doesn't work.
    // https://github.com/WebAssembly/binaryen/issues/8519
    // test("fullLinkJS minifies WASM with wasm-opt and hashes the filename") {
    //   object build extends TestRootModule with InMemoryHashScalaJSModule:
    //     override def scalaVersion: Simple[String] = "3.8.2"
    //     override def scalaJSExperimentalUseWebAssembly = true

    //     override def mvnDeps = Seq(
    //       mvn"com.raquo::laminar::17.0.0"
    //     )

    //     lazy val millDiscover = Discover[this.type]
    //   end build

    //   val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

    //   UnitTester(build, resourceFolder / "simple").scoped { eval =>
    //     val Right(result) = eval(build.fullLinkJS).runtimeChecked
    //     val report = result.value
    //     val outputDir = report.dest.path
    //     val outputFiles = os.list(outputDir).map(_.last).toSet

    //     // The in-memory directory holds the original (unoptimized) wasm
    //     val originalWasmNames = build.inMemoryOutputDirectory.fileNames().filter(_.endsWith(".wasm"))
    //     assert(originalWasmNames.size == 1)
    //     val originalWasmName = originalWasmNames.head
    //     val originalSize = build.inMemoryOutputDirectory.content(originalWasmName).get.remaining().toLong

    //     // Output should have a hashed wasm file (not the original name)
    //     assert(!outputFiles.contains(originalWasmName))
    //     val hashedWasm = outputFiles.find(f => f.endsWith(".wasm") && f.contains("."))
    //     if hashedWasm.isEmpty then
    //       throw new java.lang.AssertionError(s"no hashed .wasm file found in: $outputFiles")
    //     end if

    //     // The optimized wasm must be strictly smaller than the original
    //     // val optimizedSize = os.size(outputDir / hashedWasm.get)
    //     // assert(optimizedSize > 0)
    //     // if optimizedSize >= originalSize then
    //     //   throw new java.lang.AssertionError(
    //     //     s"wasm-opt did not reduce size: original=$originalSize, optimized=$optimizedSize"
    //     //   )
    //     // end if

    //     // JS loader files must reference the hashed wasm filename
    //     val jsFiles = outputFiles.filter(_.endsWith(".js"))
    //     assert(jsFiles.nonEmpty)
    //     jsFiles.foreach { jsName =>
    //       val content = os.read(outputDir / jsName)
    //       if content.contains(originalWasmName) then
    //         throw new java.lang.AssertionError(
    //           s"$jsName still references original wasm name $originalWasmName"
    //         )
    //       end if
    //     }
    //   }
    // }
  }
end SiteWasmTests
