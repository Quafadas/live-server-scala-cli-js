package io.github.quafadas.sjsls

import java.security.MessageDigest

import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

import mill.*
import mill.api.Result
import mill.api.Task.Simple
import mill.api.TaskCtx
import mill.scalajslib.api.*
import mill.scalajslib.config.ScalaJSConfigModule
import os.temp

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

  // Conservative Terser config tuned for Scala.js ESModule output.
  // See https://github.com/Quafadas/live-server-scala-cli-js/issues/63 for the full rationale and an aggressive
  // alternative. Key decisions: enable module mode, preserve class/function names for reflection and stack traces,
  // disable property mangling and all unsafe transforms.
  def terserConfig = Task {
    os.write(
      Task.dest / "terser.config.json",
      """{
        |  "ecma": 2020,
        |  "module": true,
        |  "compress": {
        |    "module": true,
        |    "ecma": 2015,
        |    "toplevel": true,
        |    "passes": 1,
        |    "hoist_props": false,
        |    "pure_getters": "strict",
        |    "reduce_funcs": true,
        |    "reduce_vars": true,
        |    "collapse_vars": true,
        |    "sequences": true,
        |    "side_effects": true,
        |    "conditionals": true,
        |    "comparisons": true,
        |    "evaluate": true,
        |    "drop_console": false,
        |    "keep_classnames": true,
        |    "keep_fnames": true
        |  },
        |  "mangle": {
        |    "module": true,
        |    "toplevel": true,
        |    "properties": false,
        |    "keep_classnames": true,
        |    "keep_fnames": true
        |  },
        |  "format": {
        |    "comments": false,
        |    "beautify": false
        |  }
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
    Task.log.info(s"Running fullLinkJS with content hashing on ${report.dest.path}...")
    val minify = scalaJSMinify()
    val sourceMap = scalaJSSourceMap()

    if scalaJSExperimentalUseWebAssembly() then
      FileBasedContentHashScalaJSModule.processWasmFullLink(report, Task.dest, wasmOptFlags(), minify, sourceMap)
    else if minify then
      FileBasedContentHashScalaJSModule.processTerserFullLink(report, Task.dest, terserConfig().path, sourceMap)
    else FileBasedContentHashScalaJSModule.applyContentHash(report, Task.dest)
    end if
  }

end FileBasedContentHashScalaJSModule

object FileBasedContentHashScalaJSModule:

  /** Process WASM fullLinkJS output: optionally run wasm-opt, content-hash .wasm files, patch JS loaders.
    *
    * @param report
    *   the linker report whose `dest.path` contains the raw linker output
    * @param destDir
    *   directory to write hashed output files into
    * @param flags
    *   wasm-opt flags (e.g. `Seq("-O2", "-all")`)
    * @param minify
    *   whether to run wasm-opt (if false, .wasm files are just content-hashed)
    * @param sourceMap
    *   whether to process source maps alongside .wasm files
    */
  def processWasmFullLink(
      report: Report,
      destDir: os.Path,
      flags: Seq[String],
      minify: Boolean,
      sourceMap: Boolean
  ): Report =
    val srcDir = report.dest.path
    os.makeDir.all(destDir)
    val digest = MessageDigest.getInstance("SHA-256")
    // Use a system temp dir (guaranteed no spaces in path) so wasm-opt is happy.
    val tempDir = os.temp.dir()
    try
      val wasmRenames = mutable.Map.empty[String, String]
      os.list(srcDir)
        .filter(os.isFile)
        .foreach {
          f =>
            f.ext match
              case "wasm" =>
                val tempOutPath = tempDir / f.last
                val tempOutPathMap = tempDir / (f.last + ".map")
                if minify then
                  val mapFlags: Seq[String] =
                    if sourceMap then Seq("-ism", s"${f}.map", "-osm", tempOutPathMap.toString)
                    else Seq.empty[String]
                  os.proc(
                      "wasm-opt",
                      List(f.toString) ++ flags.toList ++ List("-o", tempOutPath.toString) ++ mapFlags
                    )
                    .call(stdout = os.Inherit, stderr = os.Inherit, mergeErrIntoOut = true, check = false)
                else
                  os.copy.over(f, tempOutPath)
                  if sourceMap then os.copy.over(f / os.up / (f.last + ".map"), tempOutPathMap)
                  end if
                end if

                val optimisedBytes = os.read.bytes(tempOutPath)
                val hash = digest.digest(optimisedBytes).take(8).map("%02x".format(_)).mkString
                val hashedName = s"${f.baseName}.$hash.wasm"
                wasmRenames(f.last) = hashedName
                os.write.over(destDir / hashedName, optimisedBytes)
                if sourceMap && minify then os.copy.over(tempOutPathMap, destDir / (hashedName + ".map"))
                end if

              case "js" =>
                os.copy.over(f, destDir / f.last)
              case _ => ()
            end match
        }

      // Patch wasm references in JS loader files so they point to the hashed wasm filename.
      os.list(destDir)
        .filter(p => os.isFile(p) && p.ext == "js" && p.last.contains("main"))
        .foreach {
          jsFile =>
            val patched = rewriteJsReferences(os.read(jsFile), wasmRenames.toMap)
            os.write.over(jsFile, patched)
        }

      Report(report.publicModules, PathRef(destDir))
    finally os.remove.all(tempDir)
    end try
  end processWasmFullLink

  /** Run terser on JS files, then apply content hashing.
    *
    * @param report
    *   the linker report whose `dest.path` contains the raw linker output
    * @param destDir
    *   directory to write hashed output files into
    * @param terserConfigPath
    *   path to the terser JSON config file
    * @param sourceMap
    *   whether to process source maps alongside JS files
    */
  def processTerserFullLink(
      report: Report,
      destDir: os.Path,
      terserConfigPath: os.Path,
      sourceMap: Boolean
  ): Report =
    val srcDir = report.dest.path
    val tempDir = os.temp.dir(deleteOnExit = false)
    try
      val jsFiles = os.list(srcDir).filter(p => os.isFile(p) && p.ext == "js")
      val futures = jsFiles.map {
        f =>
          Future {
            val outPath = tempDir / f.last
            val smArgs: Seq[String] =
              if sourceMap then Seq("--source-map", s"content='${f}.map',url='${f.last}.map'")
              else Seq.empty
            os.proc(
                "terser",
                f.toString,
                "-o",
                outPath.toString,
                "--config-file",
                terserConfigPath.toString,
                smArgs
              )
              .call(
                cwd = tempDir,
                mergeErrIntoOut = true,
                stdin = os.Inherit,
                stdout = os.Inherit,
                stderr = os.Inherit
              )
          }
      }
      Await.result(Future.sequence(futures), Duration.Inf)
      // Copy non-JS files (e.g. source maps not produced by terser) to temp dir.
      os.list(srcDir)
        .filter(p => os.isFile(p) && p.ext != "js")
        .foreach {
          f =>
            val dest = tempDir / f.last
            if !os.exists(dest) then os.copy(f, dest)
            end if
        }
      applyContentHash(Report(report.publicModules, PathRef(tempDir)), destDir)
    finally os.remove.all(tempDir)
    end try
  end processTerserFullLink

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
