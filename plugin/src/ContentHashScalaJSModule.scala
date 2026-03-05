package io.github.quafadas

import java.security.MessageDigest

import mill.*
import mill.scalajslib.*
import mill.scalajslib.api.*

/** A Mill module trait that adds content hashing to Scala.js linked output.
  *
  * Mix this trait into a `ScalaJSModule` to produce JS files whose names
  * include a SHA-256 content hash, e.g. `main-a1b2c3d4.js`. Internal
  * references between modules (import/require statements and
  * `sourceMappingURL` comments) are automatically rewritten to point at the
  * new hashed filenames. This allows long-lived HTTP caching while
  * guaranteeing cache busting whenever file content changes.
  *
  * @example
  * {{{
  * object app extends ScalaJSModule with ContentHashScalaJSModule {
  *   def scalaVersion   = "3.3.6"
  *   def scalaJSVersion = "1.19.0"
  * }
  * }}}
  *
  * Running `mill app.fastLinkJS` (or `fullLinkJS`) will produce hashed files
  * in the task output directory.
  */
trait ContentHashScalaJSModule extends ScalaJSModule:

  override def fastLinkJS: T[Report] = Task(persistent = true) {
    ContentHashScalaJSModule.applyContentHash(super.fastLinkJS(), Task.dest)
  }

  override def fullLinkJS: T[Report] = Task(persistent = true) {
    ContentHashScalaJSModule.applyContentHash(super.fullLinkJS(), Task.dest)
  }

end ContentHashScalaJSModule

object ContentHashScalaJSModule:

  /** Post-process a `Report` by computing SHA-256 content hashes for every
    * emitted `.js` file, renaming each file to include the hash, rewriting
    * all intra-bundle references, and returning an updated `Report`.
    */
  def applyContentHash(report: Report, destDir: os.Path): Report =
    val srcDir = report.dest.path
    os.makeDir.all(destDir)

    // Collect all JS files in the source directory
    val jsFiles = os.list(srcDir).filter(p => os.isFile(p) && p.ext == "js")

    // Compute hash → new name mapping for every JS file
    val jsHashMapping: Map[String, String] = jsFiles.map { f =>
      val bytes   = os.read.bytes(f)
      val hashed  = computeContentHash(bytes)
      val newName = s"${f.baseName}.$hashed.${f.ext}"
      (f.last, newName)
    }.toMap

    // A combined mapping that also covers `.js.map` names so that
    // sourceMappingURL lines and source-map `"file"` fields are updated.
    val fullMapping: Map[String, String] = jsHashMapping.flatMap { case (orig, hashed) =>
      Seq(
        orig         -> hashed,
        s"$orig.map" -> s"$hashed.map"
      )
    }

    // Write hashed JS files with rewritten intra-bundle references
    jsFiles.foreach { f =>
      val originalContent = os.read(f)
      val rewritten       = rewriteJsReferences(originalContent, fullMapping)
      os.write(destDir / jsHashMapping(f.last), rewritten.getBytes("UTF-8"))
    }

    // Copy / rename remaining files (source maps, wasm, etc.)
    val copiedSoFar = jsHashMapping.keySet
    os.list(srcDir)
      .filter(p => os.isFile(p) && !copiedSoFar.contains(p.last))
      .foreach { f =>
        val newName = fullMapping.getOrElse(f.last, f.last)
        os.copy(f, destDir / newName)
      }

    // Build an updated Report that names the hashed files
    val updatedModules = report.publicModules.map { m =>
      Report.Module(
        moduleID      = m.moduleID,
        jsFileName    = jsHashMapping.getOrElse(m.jsFileName, m.jsFileName),
        sourceMapName = m.sourceMapName.map(sm => fullMapping.getOrElse(sm, sm)),
        moduleKind    = m.moduleKind
      )
    }

    Report(updatedModules, PathRef(destDir))
  end applyContentHash

  /** Compute a short (16 hex character) SHA-256 prefix of the given bytes. */
  def computeContentHash(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).take(8).map("%02x".format(_)).mkString

  /** Replace every occurrence of an original filename (quoted or in a
    * `sourceMappingURL` comment) with the corresponding hashed filename.
    *
    * Handles:
    *   - Double-quoted import/require arguments: `"original.js"`
    *   - Single-quoted import/require arguments: `'original.js'`
    *   - Inline source-map comments: `//# sourceMappingURL=original.js.map`
    */
  def rewriteJsReferences(content: String, mapping: Map[String, String]): String =
    mapping.foldLeft(content) { case (acc, (orig, hashed)) =>
      acc
        .replace(s""""$orig"""", s""""$hashed"""")
        .replace(s"'$orig'", s"'$hashed'")
        .replace(s"sourceMappingURL=$orig", s"sourceMappingURL=$hashed")
    }

end ContentHashScalaJSModule
