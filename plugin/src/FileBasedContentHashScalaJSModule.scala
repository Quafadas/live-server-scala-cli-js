package io.github.quafadas

import java.security.MessageDigest

import scala.collection.mutable

import mill.*
import mill.api.Result
import mill.api.Task.Simple
import mill.api.TaskCtx
import mill.scalajslib.api.*
import mill.scalajslib.config.ScalaJSConfigModule

/** A Mill module trait that adds content hashing to Scala.js linked output.
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
  * object app extends ScalaJSModule with FileBasedContentHashScalaJSModule {
  *   def scalaVersion   = "3.3.6"
  *   def scalaJSVersion = "1.19.0"
  * }
  *   }}}
  *
  * Running `mill app.fastLinkJS` (or `fullLinkJS`) will produce hashed files in the task output directory, e.g.
  * `out/app/fastLinkJS.dest/main.a1b2c3d4.js`.
  */
trait FileBasedContentHashScalaJSModule extends ScalaJSConfigModule:
  override def moduleKind: Simple[ModuleKind] = ModuleKind.ESModule

  /** Flags passed to `wasm-opt` when minifying WASM output during `fullLinkJS`.
    *
    * The `-all` flag is mandatory — Scala.js emits WASM features that `wasm-opt` rejects unless all features are
    * enabled. Override to reduce the optimisation level (e.g. `Seq("-O2", "-all")`).
    */
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

  override def fastLinkJS: Task.Simple[Report] = Task {
    val report = super.fastLinkJS()
    val hashedReport = FileBasedContentHashScalaJSModule.applyContentHash(report, Task.dest)
    hashedReport
  }

  /** Full link with wasm-opt minification (WASM path) or standard content-hashed output.
    *
    * When the linker produces `.wasm` files, each is optimised with `wasm-opt` using [[wasmOptFlags]] and given a
    * content-hashed filename before the rest of the output is processed by
    * [[FileBasedContentHashScalaJSModule.applyContentHash]]. The `-all` flag is mandatory for Scala.js WASM output —
    * `wasm-opt` must be available on `$$PATH`.
    *
    * For non-WASM output the report is returned unchanged (same behaviour as the default `fullLinkJS`).
    */
  override def fullLinkJS: Task.Simple[Report] = Task {
    val report = super.fullLinkJS()
    val srcDir = report.dest.path
    val wasmFiles = os.list(srcDir).filter(p => os.isFile(p) && p.ext == "wasm")

    if wasmFiles.nonEmpty then
      val flags = wasmOptFlags()
      val digest = MessageDigest.getInstance("SHA-256")
      // Copy everything to a temp dir so we can modify files without touching super's dest.
      val tempDir = os.temp.dir()
      try
        os.list(srcDir).foreach(f => os.copy(f, tempDir / f.last))

        wasmFiles.foreach {
          f =>
            val tempWasm = tempDir / f.last
            val tempOut = tempDir / "output.wasm"
            os.proc(List("wasm-opt", tempWasm.toString) ++ flags.toList ++ List("-o", tempOut.toString))
              .call(stdout = os.Inherit, stderr = os.Inherit)
            val originalSize = os.size(tempWasm)
            val optimisedBytes = os.read.bytes(tempOut)
            val hash = digest.digest(optimisedBytes).take(8).map("%02x".format(_)).mkString
            val hashedName = s"${f.baseName}.$hash.wasm"
            os.remove(tempWasm)
            os.remove(tempOut)
            os.write(tempDir / hashedName, optimisedBytes)
            Task.log.info(f"wasm-opt ${f.last}: $originalSize → ${optimisedBytes.length} bytes → $hashedName")
        }

        val updatedReport = Report(report.publicModules, PathRef(tempDir))
        FileBasedContentHashScalaJSModule.applyContentHash(updatedReport, Task.dest)
      finally os.remove.all(tempDir)
      end try
    else report
    end if
  }

  def minified = Task {
    val full = fullLinkJS()
    val terserConfigFile = terserConfig()
    val tempDir = os.temp.dir()

    try
      val files = os.walk(full.dest.path).filter(p => os.isFile(p) && p.ext == "js")
      files.foreach {
        f =>
          Task.log.info(s"Minifying ${f}...")
          val fName = f.last
          // Strip the pre-minification content hash so applyContentHash applies a single post-min hash.
          val strippedName = FileBasedContentHashScalaJSModule.stripContentHash(fName)
          val outPath = tempDir / strippedName
          tempDir / (strippedName + ".map")
          Task.log.info(s"  → ${outPath}")

          os.proc(
              "terser",
              f.toString,
              "-o",
              outPath.toString,
              "--source-map",
              s"content='${f}.map',url='${strippedName}.map'",
              "--config-file",
              terserConfigFile.path.toString
            )
            .call(
              cwd = tempDir,
              mergeErrIntoOut = true,
              stdin = os.Inherit,
              stdout = os.Inherit,
              stderr = os.Inherit
            )
          // Copy the input source map's corresponding .map if terser didn't produce one
          // (shouldn't happen, but defensive)
          val inSizeMb = os.size(f).toDouble / (1024 * 1024)
          val outSizeMb = os.size(outPath).toDouble / (1024 * 1024)
          Task.log.info(f"Minified $fName: $inSizeMb%.4f Mb → $outSizeMb%.4f Mb")
      }

      // Build a synthetic Report pointing at the temp directory so applyContentHash can process it.
      val syntheticReport = Report(
        publicModules = full
          .publicModules
          .map {
            m =>
              Report.Module(
                moduleID = m.moduleID,
                jsFileName = FileBasedContentHashScalaJSModule.stripContentHash(m.jsFileName),
                sourceMapName = m.sourceMapName.map(FileBasedContentHashScalaJSModule.stripContentHash),
                moduleKind = m.moduleKind
              )
          },
        dest = PathRef(tempDir)
      )
      FileBasedContentHashScalaJSModule.applyContentHash(syntheticReport, Task.dest)
    finally os.remove.all(tempDir)
    end try
  }

end FileBasedContentHashScalaJSModule

object FileBasedContentHashScalaJSModule:

  /** Post-process a `Report` by computing SHA-256 content hashes for every emitted `.js` file, renaming each file to
    * include the hash, rewriting all intra-bundle references, and returning an updated `Report`.
    *
    * `report.dest.path` is treated as the source directory (where the Scala.js linker wrote unhashed output). Hashed
    * files are written to `destDir`.
    *
    * Files are processed in topological order (dependency-first) so that when a file's imports are rewritten to use
    * hashed names the hash of each rewritten file is computed on its final content.
    */
  def applyContentHash(report: Report, destDir: os.Path): Report =
    val srcDir = report.dest.path
    os.makeDir.all(destDir)

    val jsFiles = os.list(srcDir).filter(p => os.isFile(p) && p.ext == "js")
    val jsFileNames = jsFiles.map(_.last).toSet

    // Build dependency graph: for each JS file, which other JS files does it import?
    val fileDeps: Map[String, Set[String]] = jsFiles
      .map {
        f =>
          val imported = parseJsImports(os.read(f)).filter(jsFileNames.contains)
          (f.last, imported.toSet)
      }
      .toMap

    // Process files in topological order: dependencies come before the files that import them.
    val sortedNames = topologicalSort(jsFiles.map(_.last).toList, fileDeps)

    // Accumulate originalName → hashedName as we process each file.
    val jsHashMapping = mutable.LinkedHashMap.empty[String, String]

    sortedNames.foreach {
      name =>
        val f = srcDir / name
        val content = os.read(f)

        // TODO: This re-wwrites across the entire file. In scalaJS, we know that these referenecs appers _only_ in the header.
        // Rewrite cross-module import references using the hashes we already know.
        val rewrittenContent = rewriteJsReferences(content, jsHashMapping.toMap)

        // Hash the rewritten content (so the filename hash reflects the final content).
        val hash = computeContentHash(rewrittenContent.getBytes("UTF-8"))
        // Replace "-" with "_" in base name: terser struggles with hyphens in external source maps.
        val hashedName = s"${f.baseName.replace("-", "_")}.$hash.${f.ext}"
        jsHashMapping(name) = hashedName

        // Also update the sourceMappingURL comment that points to this file's own map.
        val finalContent = rewrittenContent.replace(
          "sourceMappingURL=" + name + ".map",
          "sourceMappingURL=" + hashedName + ".map"
        )

        os.write.over(destDir / hashedName, finalContent.getBytes("UTF-8"))
    }

    // Build full mapping including source-map file renames.
    val fullMapping: Map[String, String] = jsHashMapping
      .flatMap {
        case (orig, hashed) =>
          Seq(orig -> hashed, orig + ".map" -> (hashed + ".map"))
      }
      .toMap

    // Copy remaining files (source maps, wasm, etc.) with renamed paths where applicable.
    val copiedSoFar = jsHashMapping.keySet
    os.list(srcDir)
      .filter(p => os.isFile(p) && !copiedSoFar.contains(p.last))
      .foreach {
        f =>
          val newName = fullMapping.getOrElse(f.last, f.last)
          os.copy.over(f, destDir / newName)
      }

    // Build updated Report with hashed filenames.
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

    Report(updatedModules, PathRef(destDir))
  end applyContentHash

  /** Extract basenames of JS files imported by this module's content.
    *
    * Scala.js ESModule output always uses `"./module.js"` for cross-module imports. We also handle the no-prefix
    * `"module.js"` form for completeness.
    */
  def parseJsImports(content: String): Seq[String] =
    // Matches: from"./foo.js"  from "./foo.js"  import"./foo.js"  import "./foo.js"
    // Also handles no-prefix forms: from"foo.js"  from "foo.js"
    // Captures only the basename (strips the "./" prefix when present).
    val pattern = """(?:from|import)\s*["'](?:\./)?([^"'/\s]+\.js)["']""".r
    pattern.findAllMatchIn(content).map(_.group(1)).toSeq
  end parseJsImports

  /** Sort filenames topologically so each file appears after all its dependencies.
    *
    * Uses Kahn's algorithm. Cycles (impossible in valid ES-module graphs) are handled gracefully by appending any
    * remaining nodes at the end.
    */
  def topologicalSort(names: List[String], deps: Map[String, Set[String]]): List[String] =
    val inDegree = mutable.Map(names.map(_ -> 0)*)
    val adjList = mutable.Map(names.map(_ -> mutable.ListBuffer.empty[String])*)

    for
      (node, nodeDeps) <- deps
      dep <- nodeDeps
      if adjList.contains(dep)
    do
      adjList(dep) += node
      inDegree(node) = inDegree(node) + 1
    end for

    val queue = mutable.Queue(names.filter(n => inDegree(n) == 0)*)
    val result = mutable.ListBuffer.empty[String]

    while queue.nonEmpty do
      val node = queue.dequeue()
      result += node
      adjList(node).foreach {
        neighbor =>
          inDegree(neighbor) -= 1
          if inDegree(neighbor) == 0 then queue.enqueue(neighbor)
          end if
      }
    end while

    // Append any remaining nodes (handles cycles gracefully).
    val seen = result.toSet
    result ++= names.filterNot(seen.contains)
    result.toList
  end topologicalSort

  /** Compute a short (16 hex character) SHA-256 prefix of the given bytes. */
  def computeContentHash(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).take(8).map("%02x".format(_)).mkString
  end computeContentHash

  /** Remove the content hash segment from a hashed filename.
    *
    * A content-hashed filename has the form `<base>.<16hexchars>.<ext>`, e.g. `main.a1b2c3d4e5f6g7h8.js`. This method
    * strips the hash segment and returns `<base>.<ext>`, e.g. `main.js`.
    *
    * For source map files (`*.js.map`), the form is `<base>.<16hexchars>.js.map` and the hash is the third-to-last
    * segment.
    *
    * If the filename does not match the expected pattern, it is returned unchanged.
    */
  def stripContentHash(name: String): String =
    val hexPattern = "^[0-9a-f]{16}$".r
    val parts = name.split('.')
    // For .js.map files: parts = [base..., hash, js, map] — hash is at length-3
    // For .js files:     parts = [base..., hash, js]       — hash is at length-2
    val hashIdx =
      if parts.length >= 4 && parts(parts.length - 1) == "map" && parts(parts.length - 2) == "js" then parts.length - 3
      else if parts.length >= 3 then parts.length - 2
      else -1

    if hashIdx > 0 && hexPattern.matches(parts(hashIdx)) then
      (parts.take(hashIdx) ++ parts.drop(hashIdx + 1)).mkString(".")
    else name
    end if
  end stripContentHash

  /** Replace quoted module names and sourceMappingURL references throughout `content`.
    *
    * Handles:
    *   - Double-quoted bare style: `"original.js"` → `"hashed.js"`
    *   - Single-quoted bare style: `'original.js'` → `'hashed.js'`
    *   - Double-quoted relative style: `"./original.js"` → `"./hashed.js"` (Scala.js ESModule default)
    *   - Single-quoted relative style: `'./original.js'` → `'./hashed.js'`
    *   - Source-map comment: `sourceMappingURL=original.js.map` → `sourceMappingURL=hashed.js.map`
    */
  def rewriteJsReferences(content: String, mapping: Map[String, String]): String =
    mapping.foldLeft(content) {
      case (acc, (orig, hashed)) =>
        acc
          .replace("\"" + orig + "\"", "\"" + hashed + "\"")
          .replace("'" + orig + "'", "'" + hashed + "'")
          .replace("\"./" + orig + "\"", "\"./" + hashed + "\"")
          .replace("'./" + orig + "'", "'./" + hashed + "'")
          .replace("sourceMappingURL=" + orig, "sourceMappingURL=" + hashed)
    }

end FileBasedContentHashScalaJSModule
