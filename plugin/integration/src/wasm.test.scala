package io.github.quafadas.millSite

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import io.github.quafadas.InMemoryHashScalaJSModule
import io.github.quafadas.FileBasedContentHashScalaJSModule
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

    test("InMemoryHashScalaJSModule fullLinkJS runs wasm-opt and hashes the optimised binary") {
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
          // Run fastLinkJS first to capture the unoptimised wasm size.
          val Right(_) = eval(build.fastLinkJS).runtimeChecked
          val fastWasmNames = build.inMemoryOutputDirectory.fileNames().filter(_.endsWith(".wasm"))
          if fastWasmNames.size != 1 then
            throw new java.lang.AssertionError(s"Expected exactly 1 wasm file after fastLinkJS, got: $fastWasmNames")
          end if
          val fastBuf = build.inMemoryOutputDirectory.content(fastWasmNames.head).get
          val fastWasmSize = fastBuf.remaining().toLong
          val fastWasmHashedName = fastWasmNames.head

          // Run fullLinkJS: wasm-opt should produce a smaller binary with a different hash.
          val Right(_) = eval(build.fullLinkJS).runtimeChecked
          val fullWasmNames = build.inMemoryOutputDirectory.fileNames().filter(_.endsWith(".wasm"))
          if fullWasmNames.size != 1 then
            throw new java.lang.AssertionError(s"Expected exactly 1 wasm file after fullLinkJS, got: $fullWasmNames")
          end if
          val fullWasmName = fullWasmNames.head
          val fullBuf = build.inMemoryOutputDirectory.content(fullWasmName).get
          val fullWasmSize = fullBuf.remaining().toLong

          // The name must be content-hashed (base.<hash>.wasm), not the bare original name.
          val nameParts = fullWasmName.stripSuffix(".wasm").split('.')
          if nameParts.length < 2 then
            throw new java.lang.AssertionError(
              s"Expected hashed wasm filename like 'main.<hash>.wasm', got: $fullWasmName"
            )
          end if

          // The hash must reflect the optimised binary — different from the fast-link hash.
          if fullWasmName == fastWasmHashedName then
            throw new java.lang.AssertionError(
              s"fullLinkJS wasm hash should differ from fastLinkJS hash, both were: $fastWasmHashedName"
            )
          end if

          // The optimised binary must be strictly smaller.
          if fullWasmSize >= fastWasmSize then
            throw new java.lang.AssertionError(
              s"wasm-opt did not reduce size: fastLinkJS=$fastWasmSize bytes, fullLinkJS=$fullWasmSize bytes"
            )
          end if
      }
    }

    test("FileBasedContentHashScalaJSModule fullLinkJS runs wasm-opt and hashes the optimised binary") {
      object build extends TestRootModule with FileBasedContentHashScalaJSModule:
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
          // fastLinkJS: produces the unoptimised wasm (copied by applyContentHash with original name).
          val Right(fastResult) = eval(build.fastLinkJS).runtimeChecked
          val fastDir = fastResult.value.dest.path
          val fastWasmFiles = os.list(fastDir).filter(p => os.isFile(p) && p.ext == "wasm")
          if fastWasmFiles.size != 1 then
            throw new java.lang.AssertionError(s"Expected exactly 1 wasm file after fastLinkJS, got: $fastWasmFiles")
          end if
          val fastWasmSize = os.size(fastWasmFiles.head)

          // fullLinkJS: runs wasm-opt and emits a content-hashed .wasm file.
          val Right(fullResult) = eval(build.fullLinkJS).runtimeChecked
          val fullDir = fullResult.value.dest.path
          val fullWasmFiles = os.list(fullDir).filter(p => os.isFile(p) && p.ext == "wasm")
          if fullWasmFiles.size != 1 then
            throw new java.lang.AssertionError(s"Expected exactly 1 wasm file after fullLinkJS, got: $fullWasmFiles")
          end if
          val fullWasm = fullWasmFiles.head
          val fullWasmSize = os.size(fullWasm)

          // The file must be content-hashed: base.<hash>.wasm.
          val nameParts = fullWasm.baseName.split('.')
          if nameParts.length < 2 then
            throw new java.lang.AssertionError(
              s"Expected hashed wasm filename like 'main.<hash>.wasm', got: ${fullWasm.last}"
            )
          end if

          // The optimised binary must be strictly smaller.
          if fullWasmSize >= fastWasmSize then
            throw new java.lang.AssertionError(
              s"wasm-opt did not reduce size: fastLinkJS=$fastWasmSize bytes, fullLinkJS=$fullWasmSize bytes"
            )
          end if
      }
    }
  }
end SiteWasmTests
