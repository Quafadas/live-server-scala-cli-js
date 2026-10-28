package io.github.quafadas.sjsls

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
trait InMemoryFastLinkHashScalaJSModule extends FileBasedContentHashScalaJSModule with ScalaJSConfigModule:
  val inMemoryOutputDirectory: MemOutputDirectory = MemOutputDirectory()
  val hashedOutputFiles: ConcurrentHashMap[String, Array[Byte]] = new ConcurrentHashMap()

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

  /** Avoids disk IO by keeping everything in memory.
    */
  override def fastLinkJS = Task {
    val report = super.fastLinkJS()
    hashedOutputFiles.clear()
    if scalaJSExperimentalUseWebAssembly() then
      val wasmReport = processWasm(report)
      // Populate hashedOutputFiles from inMemoryOutputDirectory after WASM processing
      inMemoryOutputDirectory
        .fileNames()
        .foreach {
          name =>
            Task.log.debug(s"Adding in-memory file to hashedOutputFiles: $name")
            val buf = inMemoryOutputDirectory.content(name).get
            val bytes = new Array[Byte](buf.remaining())
            buf.get(bytes)
            hashedOutputFiles.put(name, bytes)
        }
      wasmReport
    else
      // Hash each JS module on its own content; the generated index.html carries an import map from the original
      // module names to the hashed ones (see `bodyHtmlFromReport`), so imports between modules are left as-is.
      import mill.scalajslib.ContentHashScalaJSModule as C

      val linked = inMemoryOutputDirectory
        .fileNames()
        .map {
          name =>
            val buf = inMemoryOutputDirectory.content(name).get
            val bytes = new Array[Byte](buf.remaining())
            buf.get(bytes)
            name -> bytes
        }
        .toMap
      val hashed = ContentHasher.hashWithImportMap(linked, C.sanitiseHashedBaseName)
      hashed.files.foreach((name, bytes) => hashedOutputFiles.put(name, bytes))
      os.write
        .over(Task.dest / InMemoryFastLinkHashScalaJSModule.importMapFile, upickle.default.write(hashed.importMap))

      val updatedModules = report
        .publicModules
        .map {
          m =>
            Report.Module(
              moduleID = m.moduleID,
              jsFileName = hashed.importMap.getOrElse(m.jsFileName, m.jsFileName),
              sourceMapName =
                m.sourceMapName.map(sm => hashed.importMap.get(sm.stripSuffix(".map")).fold(sm)(_ + ".map")),
              moduleKind = m.moduleKind
            )
        }

      api.Report(updatedModules, PathRef(Task.dest))
    end if
  }

end InMemoryFastLinkHashScalaJSModule

object InMemoryFastLinkHashScalaJSModule:
  /** Written alongside `fastLinkJS` output: a JSON object of original -> content-hashed JS module names. */
  val importMapFile = "importmap.json"
end InMemoryFastLinkHashScalaJSModule
