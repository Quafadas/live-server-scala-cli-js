package mill.scalajslib

import java.security.MessageDigest
import scala.collection.mutable

import mill.*
import mill.api.Result
import mill.api.TaskCtx
import mill.scalajslib.api.*
import mill.scalajslib.worker.ScalaJSWorker
import mill.api.Task.Simple


import java.net.InetSocketAddress
import java.nio.file.Path
import com.sun.net.httpserver.SimpleFileServer

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
trait FileBasedContentHashScalaJSModule extends ScalaJSModule:

  override def moduleKind: Simple[ModuleKind] = ModuleKind.ESModule

  def terserConfig = Task{
    os.write(Task.dest / "terser.config.json",
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
        |""".stripMargin)
    PathRef(Task.dest / "terser.config.json")
  }

  def minified = Task {
    val full = fullLinkJS()
    val terserConfigFile = terserConfig()
    val tempDir = os.temp.dir()

    try {
      val files = os.walk(full.dest.path).filter(p => os.isFile(p) && p.ext == "js")
      files.foreach { f =>
        Task.log.info(s"Minifying ${f}...")
        val fName = f.last
        // Strip the pre-minification content hash so applyContentHash applies a single post-min hash.
        val strippedName = FileBasedContentHashScalaJSModule.stripContentHash(fName)
        val outPath = tempDir / strippedName
        val outMapPath = tempDir / (strippedName + ".map")
        Task.log.info(s"  → ${outPath}")

        os.proc(
          "terser",
          f.toString,
          "-o", outPath.toString,
          "--source-map", s"content='${f}.map',url='${strippedName}.map'",
          "--config-file", terserConfigFile.path.toString
        ).call(
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
        publicModules = full.publicModules.map { m =>
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
      PathRef(Task.dest)
    } finally {
      os.remove.all(tempDir)
    }
  }

  /** Override [[linkJs]] to capture linker output in a temporary directory, compute SHA-256 content hashes, rewrite
    * intra-bundle references, and write only the hashed files to the task output directory (`ctx.dest`).
    *
    * This is the linker-wrapper approach: instead of post-processing files that a prior task has already written to
    * disk, we intercept at the linking step itself — redirecting [[ScalaJSWorker.link]] output to a temporary directory
    * — so only the final hashed artefacts ever land in Mill's task output directory.
    *
    * The trait must reside in `mill.scalajslib` because both [[ScalaJSWorker]] and [[linkJs]] are declared
    * `private[scalajslib]`.
    */
  private[scalajslib] override def linkJs(
      worker: ScalaJSWorker,
      toolsClasspath: Seq[PathRef],
      runClasspath: Seq[PathRef],
      mainClass: Result[String],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle,
      outputPatterns: OutputPatterns,
      minify: Boolean,
      importMap: Seq[ESModuleImportMapping],
      experimentalUseWebAssembly: Boolean
  )(implicit ctx: TaskCtx): Result[Report] =
    val tempDir = os.temp.dir()
    try
      worker
        .link(
          toolsClasspath = toolsClasspath,
          runClasspath = runClasspath,
          dest = tempDir.toIO,
          main = mainClass,
          forceOutJs = forceOutJs,
          testBridgeInit = testBridgeInit,
          isFullLinkJS = isFullLinkJS,
          optimizer = optimizer,
          sourceMap = sourceMap,
          moduleKind = moduleKind,
          esFeatures = esFeatures,
          moduleSplitStyle = moduleSplitStyle,
          outputPatterns = outputPatterns,
          minify = minify,
          importMap = importMap,
          experimentalUseWebAssembly = experimentalUseWebAssembly
        )
        .map {
          report =>
            FileBasedContentHashScalaJSModule.applyContentHash(report, ctx.dest)
        }
    finally os.remove.all(tempDir)
    end try
  end linkJs

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

        //TODO: This re-wwrites across the entire file. In scalaJS, we know that these referenecs appers _only_ in the header.
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
      if parts.length >= 4 && parts(parts.length - 1) == "map" && parts(parts.length - 2) == "js" then
        parts.length - 3
      else if parts.length >= 3 then
        parts.length - 2
      else -1

    if hashIdx > 0 && hexPattern.matches(parts(hashIdx)) then
      (parts.take(hashIdx) ++ parts.drop(hashIdx + 1)).mkString(".")
    else name
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
