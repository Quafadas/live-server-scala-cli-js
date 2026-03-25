package io.github.quafadas

import java.nio.ByteBuffer
import java.security.MessageDigest

import mill.*
import mill.api.Task.Simple
import mill.scalajslib.api
import mill.scalajslib.api.ModuleKind
import mill.scalajslib.api.Report
import mill.scalajslib.config.ScalaJSConfigModule
import mill.api.TaskCtx.Log
import org.scalajs.linker.interface.OutputDirectory

/** A Mill module trait that adds content hashing to Scala.js linked output.
  *
  * Mix this trait into a `ScalaJSModule` to produce JS files whose names include a SHA-256 content hash, e.g.
  * `main.a1b2c3d4.js`. Internal references between modules (import/require statements and `sourceMappingURL` comments)
  * are automatically rewritten to point at the new hashed filenames. This allows long-lived HTTP caching while
  * guaranteeing cache busting whenever file content changes.
  *
  * @note
  *   This trait is placed in the `mill.scalajslib` package (vendored alongside Mill) so that it can access the
  *   `private[scalajslib]` members [[ScalaJSWorker]] and [[ScalaJSModule.linkJs]], which are intentionally hidden from
  *   external packages. No Mill sources are modified; we only add a new trait that overrides one `private[scalajslib]`
  *   method.
  *
  * @example
  *   {{{
  * object app extends ScalaJSModule with ContentHashScalaJSModule {
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
    println(s"Using in-memory output directory") // debug log
    Some(inMemoryOutputDirectory)

  override def moduleKind: Simple[ModuleKind] = ModuleKind.ESModule

  def wasmOptFlags: Task[Seq[String]] = Task(Seq("-O2"))

  /**
   *
   */
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
    Task.log.debug(s"Renamed WASM files: ${jsRenames.map { case (orig, hashed, _) => s"$orig -> $hashed" }.mkString(", ")}")
    api.Report(updatedModules, report.dest)
  end processWasm

  override def fastLinkJS = Task {
    val report = super.fastLinkJS()
    if scalaJSExperimentalUseWebAssembly() then processWasm(report)
    else
      // In non-WASM mode, just hash the JS files without optimization
      ???
    end if
  }

end InMemoryHashScalaJSModule
