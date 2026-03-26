package io.github.quafadas

import java.nio.ByteBuffer
import java.security.MessageDigest

import scala.collection.mutable

import org.scalajs.linker.interface.OutputDirectory

import mill.*
import mill.api.Task.Simple
import mill.api.TaskCtx.Log
import mill.scalajslib.api
import mill.scalajslib.api.ModuleKind
import mill.scalajslib.api.Report
import mill.scalajslib.config.ScalaJSConfigModule

/** A Mill module trait that adds content hashing to Scala.js linked output, using an in-memory linker output directory.
  *
  * Mix this trait into a `ScalaJSModule` to produce JS (or WASM) files whose names include a SHA-256 content hash, e.g.
  * `main.a1b2c3d4.js`. Internal references between modules are automatically rewritten to use the hashed names,
  * enabling long-lived HTTP caching with automatic cache busting on content changes.
  *
  * When `scalaJSExperimentalUseWebAssembly` is enabled, `fullLinkJS` additionally runs `wasm-opt` on the emitted
  * `.wasm` binary before computing the content hash, so the stored hash always reflects the production-optimised
  * artifact. Override [[wasmOptFlags]] to customise the optimisation level. The `-all` flag is mandatory for Scala.js
  * WASM output — `wasm-opt` must be present on `$$PATH`.
  *
  * @note
  *   This trait is placed in the `mill.scalajslib` package (vendored alongside Mill) so that it can access the
  *   `private[scalajslib]` members [[ScalaJSWorker]] and [[ScalaJSModule.linkJs]], which are intentionally hidden from
  *   external packages. No Mill sources are modified; we only add a new trait that overrides one `private[scalajslib]`
  *   method.
  *
  * @example
  *   {{{
  * object app extends ScalaJSModule with InMemoryHashScalaJSModule {
  *   def scalaVersion   = "3.3.6"
  *   def scalaJSVersion = "1.19.0"
  * }
  *   }}}
  *
  * Running `mill app.fastLinkJS` (or `fullLinkJS`) will produce hashed files in the task output directory, e.g.
  * `out/app/fastLinkJS.dest/main.a1b2c3d4.js`.
  */
trait InMemoryHashScalaJSModule extends ScalaJSConfigModule:
  val inMemoryOutputDirectory: MemOutputDirectory = MemOutputDirectory()

  override def customLinkerOutputDir: Option[OutputDirectory] =
    println("Using in-memory output directory") // debug log
    Some(inMemoryOutputDirectory)
  end customLinkerOutputDir

  override def moduleKind: Simple[ModuleKind] = ModuleKind.ESModule

  def wasmOptFlags: Task[Seq[String]] = Task(Seq("-O2", "-all"))

  def terserConfig = Task {
    os.write(
      Task.dest / "terser.config.json",
      """{
        |  "compress": {
        |    "passes": 2,
        |    "pure_getters": true,
        |    "unsafe": false,
        |    "unsafe_arrows": false,
        |    "unsafe_methods": false,
        |    "drop_console": false
        |  },
        |  "mangle": {
        |    "toplevel": false
        |  },
        |  "format": {
        |    "comments": false
        |  },
        |  "ecma": 2020,
        |  "module": true,
        |  "toplevel": false
        |}
        |""".stripMargin
    )
    PathRef(Task.dest / "terser.config.json")
  }

  /** */
  def processWasm(report: Report)(using logCtx: Log): Report =

    val allFiles = inMemoryOutputDirectory.fileNames()
    val wasmFiles = allFiles.filter(_.endsWith(".wasm"))
    val digest = MessageDigest.getInstance("SHA-256")

    val jsRenames = wasmFiles.map {
      name =>
        val buf = inMemoryOutputDirectory.content(name).get
        val bytes = new Array[Byte](buf.remaining())
        buf.get(bytes)
        val hash = digest.digest(bytes).take(8).map("%02x".format(_)).mkString
        val baseName = name.stripSuffix(".wasm")
        val hashedName = s"$baseName.$hash.wasm"
        (name, hashedName, bytes)
    }

    jsRenames.foreach {
      case (origName, hashedName, bytes) =>
        inMemoryOutputDirectory.remove(origName)
        inMemoryOutputDirectory.put(hashedName, ByteBuffer.wrap(bytes))
    }

    val renameMap = jsRenames.map { case (orig, hashed, _) => orig -> hashed }.toMap
    val updatedModules = report
      .publicModules
      .map {
        m =>
          renameMap.get(m.jsFileName) match
            case Some(hashed) =>
              Report.Module(
                moduleID = m.moduleID,
                jsFileName = hashed,
                sourceMapName = m.sourceMapName,
                moduleKind = m.moduleKind
              )
            case None => m
      }
    Task
      .log
      .debug(s"Renamed WASM files: ${jsRenames.map { case (orig, hashed, _) => s"$orig -> $hashed" }.mkString(", ")}")
    api.Report(updatedModules, report.dest)
  end processWasm

  /** Optimises each `.wasm` file currently held in [[inMemoryOutputDirectory]] using `wasm-opt`.
    *
    * The tool writes the optimised binary to a temporary directory, then replaces the in-memory entry so that a
    * subsequent call to [[processWasm]] hashes the optimised bytes.
    *
    * `wasm-opt` must be available on `$PATH`. The flags (including `-all`, which is mandatory for Scala.js WASM output)
    * are taken from [[wasmOptFlags]].
    */
  private def runWasmOpt(flags: Seq[String])(using logCtx: Log): Unit =
    val wasmFileNames = inMemoryOutputDirectory.fileNames().filter(_.endsWith(".wasm"))
    if wasmFileNames.nonEmpty then
      val tempDir = os.temp.dir()
      try
        wasmFileNames.foreach {
          name =>
            val buf = inMemoryOutputDirectory.content(name).get
            val bytes = new Array[Byte](buf.remaining())
            buf.get(bytes)
            val inputPath = tempDir / "input.wasm"
            val outputPath = tempDir / "output.wasm"
            os.write.over(inputPath, bytes)
            os.proc(List("wasm-opt", inputPath.toString) ++ flags.toList ++ List("-o", outputPath.toString))
              .call(stdout = os.Inherit, stderr = os.Inherit)
            val optimisedBytes = os.read.bytes(outputPath)
            inMemoryOutputDirectory.remove(name)
            inMemoryOutputDirectory.put(name, ByteBuffer.wrap(optimisedBytes))
            Task.log.info(s"wasm-opt $name: ${bytes.length} → ${optimisedBytes.length} bytes")
        }
      finally os.remove.all(tempDir)
      end try
    end if
  end runWasmOpt

  override def fastLinkJS = Task {
    val report = super.fastLinkJS()
    if scalaJSExperimentalUseWebAssembly() then processWasm(report)
    else
      // Hash JS files from the in-memory output directory, then write hashed output to Task.dest.
      import mill.scalajslib.ContentHashScalaJSModule as C

      val allFiles = inMemoryOutputDirectory.fileNames()
      val jsFileNames = allFiles.filter(_.endsWith(".js")).toSet

      def readStr(name: String): String =
        val buf = inMemoryOutputDirectory.content(name).get
        val bytes = new Array[Byte](buf.remaining())
        buf.get(bytes)
        new String(bytes, "UTF-8")
      end readStr

      def readBytes(name: String): Array[Byte] =
        val buf = inMemoryOutputDirectory.content(name).get
        val bytes = new Array[Byte](buf.remaining())
        buf.get(bytes)
        bytes
      end readBytes

      // Build dependency graph.
      val fileDeps: Map[String, Set[String]] = jsFileNames
        .map {
          name =>
            val imported = C.parseJsImports(readStr(name)).filter(jsFileNames.contains)
            (name, imported.toSet)
        }
        .toMap

      // Process in topological order (dependency-first).
      val sortedNames = C.topologicalSort(jsFileNames.toList, fileDeps)
      val jsHashMapping = mutable.LinkedHashMap.empty[String, String]

      os.makeDir.all(Task.dest)

      sortedNames.foreach {
        name =>
          val content = readStr(name)
          val rewrittenContent = C.rewriteJsReferences(content, jsHashMapping.toMap)
          val hash = C.computeContentHash(rewrittenContent.getBytes("UTF-8"))
          // Replace "-" with "_" to avoid issues with terser external source maps.
          val baseName = name.stripSuffix(".js").replace("-", "_")
          val hashedName = s"$baseName.$hash.js"
          jsHashMapping(name) = hashedName

          val finalContent = rewrittenContent.replace(
            "sourceMappingURL=" + name + ".map",
            "sourceMappingURL=" + hashedName + ".map"
          )
          os.write(Task.dest / hashedName, finalContent.getBytes("UTF-8"))
      }

      // Build full mapping including source-map renames.
      val fullMapping: Map[String, String] = jsHashMapping
        .flatMap {
          case (orig, hashed) => Seq(orig -> hashed, orig + ".map" -> (hashed + ".map"))
        }
        .toMap

      // Write remaining files (source maps, etc.) to Task.dest.
      allFiles
        .filterNot(jsFileNames.contains)
        .foreach {
          name =>
            val targetName = fullMapping.getOrElse(name, name)
            os.write(Task.dest / targetName, readBytes(name))
        }

      // Build updated Report.
      val updatedModules = report
        .publicModules
        .map {
          m =>
            Report.Module(
              moduleID = m.moduleID,
              jsFileName = jsHashMapping.getOrElse(m.jsFileName, m.jsFileName),
              sourceMapName = m.sourceMapName.map(sm => fullMapping.getOrElse(sm, sm)),
              moduleKind = m.moduleKind
            )
        }
      api.Report(updatedModules, PathRef(Task.dest))
    end if
  }

  /** Full link with wasm-opt minification (WASM path) or content-hashed JS (non-WASM path).
    *
    * When `scalaJSExperimentalUseWebAssembly` is enabled, `wasm-opt` is invoked with [[wasmOptFlags]] before the
    * content hash is applied. The `-all` flag is mandatory for Scala.js WASM output; omitting it will cause `wasm-opt`
    * to fail. `wasm-opt` must be available on `$PATH`.
    */
  override def fullLinkJS = Task {
    val report = super.fullLinkJS()
    if scalaJSExperimentalUseWebAssembly() then
      runWasmOpt(wasmOptFlags())
      processWasm(report)
    else
      // Hash JS files from the in-memory output directory, then write hashed output to Task.dest.
      import mill.scalajslib.ContentHashScalaJSModule as C

      val allFiles = inMemoryOutputDirectory.fileNames()
      val jsFileNames = allFiles.filter(_.endsWith(".js")).toSet

      def readStr(name: String): String =
        val buf = inMemoryOutputDirectory.content(name).get
        val bytes = new Array[Byte](buf.remaining())
        buf.get(bytes)
        new String(bytes, "UTF-8")
      end readStr

      def readBytes(name: String): Array[Byte] =
        val buf = inMemoryOutputDirectory.content(name).get
        val bytes = new Array[Byte](buf.remaining())
        buf.get(bytes)
        bytes
      end readBytes

      // Optionally run terser on JS files for production size reduction.
      // Gated on scalaJSMinify so users can opt out by setting `override def scalaJSMinify = Task(false)`.
      if scalaJSMinify() then
        val terserCfg = terserConfig()
        val terserTempDir = os.temp.dir()
        try
          jsFileNames.foreach {
            name =>
              val inputPath = terserTempDir / name
              os.write(inputPath, readBytes(name))
              val outputPath = terserTempDir / ("min." + name)
              os.proc(
                  "terser",
                  inputPath.toString,
                  "-o",
                  outputPath.toString,
                  "--config-file",
                  terserCfg.path.toString
                )
                .call(mergeErrIntoOut = true, stdout = os.Inherit, stderr = os.Inherit)
              val minifiedBytes = os.read.bytes(outputPath)
              inMemoryOutputDirectory.remove(name)
              inMemoryOutputDirectory.put(name, ByteBuffer.wrap(minifiedBytes))
          }
        finally os.remove.all(terserTempDir)
        end try
      end if

      // Build dependency graph.
      val fileDeps: Map[String, Set[String]] = jsFileNames
        .map {
          name =>
            val imported = C.parseJsImports(readStr(name)).filter(jsFileNames.contains)
            (name, imported.toSet)
        }
        .toMap

      // Process in topological order (dependency-first).
      val sortedNames = C.topologicalSort(jsFileNames.toList, fileDeps)
      val jsHashMapping = mutable.LinkedHashMap.empty[String, String]

      os.makeDir.all(Task.dest)

      sortedNames.foreach {
        name =>
          val content = readStr(name)
          val rewrittenContent = C.rewriteJsReferences(content, jsHashMapping.toMap)
          val hash = C.computeContentHash(rewrittenContent.getBytes("UTF-8"))
          // Replace "-" with "_" to avoid issues with terser external source maps.
          val baseName = name.stripSuffix(".js").replace("-", "_")
          val hashedName = s"$baseName.$hash.js"
          jsHashMapping(name) = hashedName

          val finalContent = rewrittenContent.replace(
            "sourceMappingURL=" + name + ".map",
            "sourceMappingURL=" + hashedName + ".map"
          )
          os.write(Task.dest / hashedName, finalContent.getBytes("UTF-8"))
      }

      // Build full mapping including source-map renames.
      val fullMapping: Map[String, String] = jsHashMapping
        .flatMap {
          case (orig, hashed) => Seq(orig -> hashed, orig + ".map" -> (hashed + ".map"))
        }
        .toMap

      // Write remaining files (source maps, etc.) to Task.dest.
      allFiles
        .filterNot(jsFileNames.contains)
        .foreach {
          name =>
            val targetName = fullMapping.getOrElse(name, name)
            os.write(Task.dest / targetName, readBytes(name))
        }

      // Build updated Report.
      val updatedModules = report
        .publicModules
        .map {
          m =>
            Report.Module(
              moduleID = m.moduleID,
              jsFileName = jsHashMapping.getOrElse(m.jsFileName, m.jsFileName),
              sourceMapName = m.sourceMapName.map(sm => fullMapping.getOrElse(sm, sm)),
              moduleKind = m.moduleKind
            )
        }
      api.Report(updatedModules, PathRef(Task.dest))
    end if
  }

end InMemoryHashScalaJSModule
