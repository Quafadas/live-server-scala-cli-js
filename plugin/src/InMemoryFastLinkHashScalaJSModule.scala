package io.github.quafadas.sjsls

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
import mill.api.BuildCtx

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
trait InMemoryFastLinkHashScalaJSModule extends FileBasedContentHashScalaJSModule with ScalaJSConfigModule:
  val inMemoryOutputDirectory: MemOutputDirectory = MemOutputDirectory()

  override def customLinkerOutputDir: Option[OutputDirectory] =
    Some(inMemoryOutputDirectory)
  end customLinkerOutputDir

  override def moduleKind: Simple[ModuleKind] = ModuleKind.ESModule

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

    // Patch wasm references in JS loader files so they point to the hashed wasm filename.
    import mill.scalajslib.ContentHashScalaJSModule as C
    inMemoryOutputDirectory
      .fileNames()
      .filter(_.endsWith(".js"))
      .foreach {
        jsName =>
          val buf = inMemoryOutputDirectory.content(jsName).get
          val bytes = new Array[Byte](buf.remaining())
          buf.get(bytes)
          val patched = C.rewriteJsReferences(new String(bytes, "UTF-8"), renameMap)
          inMemoryOutputDirectory.remove(jsName)
          inMemoryOutputDirectory.put(jsName, ByteBuffer.wrap(patched.getBytes("UTF-8")))
      }

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

  override def fullLinkJS: Simple[Report] = Task {
    val report = super[ScalaJSConfigModule].fullLinkJS()
    val tmpDir = os.temp.dir(deleteOnExit = false)
    try
      Task.log.debug(s"Full link JS: dumping in-memory files to $tmpDir")
      for f <- inMemoryOutputDirectory.fileNames() do os.write.over(tmpDir / f, inMemoryOutputDirectory.content(f).get)
      end for

      val syntheticReport = Report(report.publicModules, PathRef(tmpDir))
      val minify = scalaJSMinify()
      val sourceMap = scalaJSSourceMap()

      if scalaJSExperimentalUseWebAssembly() then
        FileBasedContentHashScalaJSModule.processWasmFullLink(
          syntheticReport,
          Task.dest,
          wasmOptFlags(),
          minify,
          sourceMap
        )
      else if minify then
        FileBasedContentHashScalaJSModule.processTerserFullLink(
          syntheticReport,
          Task.dest,
          terserConfig().path,
          sourceMap
        )
      else FileBasedContentHashScalaJSModule.applyContentHash(syntheticReport, Task.dest)
      end if
    finally os.remove.all(tmpDir)
    end try
  }

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

end InMemoryFastLinkHashScalaJSModule
