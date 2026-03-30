package io.github.quafadas.sjsls

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable

import fs2.io.file.Files
import fs2.io.file.Path

import scribe.Scribe

import cats.effect.IO
import cats.syntax.all.*

/** Utilities for computing content hashes of JavaScript files and rewriting intra-bundle import references in memory,
  * without modifying files on disk.
  *
  * This mirrors the approach used by `ContentHashScalaJSModule` in the Mill plugin, but operates entirely in memory:
  * files are read from disk, hashed, references rewritten, and the results stored in a `ConcurrentHashMap` that the
  * server can serve directly.
  */
object ContentHasher:

  /** Extract basenames of JS files imported by this module's content.
    *
    * Scala.js ESModule output always uses `"./module.js"` for cross-module imports. The no-prefix `"module.js"` form is
    * also handled for completeness.
    */
  def parseJsImports(content: String): Seq[String] =
    val pattern = """(?:from|import)\s*["'](?:\./)?([^"'/\s]+\.js)["']""".r
    pattern.findAllMatchIn(content).map(_.group(1)).toSeq
  end parseJsImports

  /** Sort filenames topologically so each file appears after all its dependencies (Kahn's algorithm).
    *
    * Cycles (impossible in valid ES-module graphs) are handled gracefully by appending remaining nodes at the end.
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

    val seen = result.toSet
    result ++= names.filterNot(seen.contains)
    result.toList
  end topologicalSort

  /** Compute a short (16 hex character) SHA-256 prefix of the given bytes. */
  def computeContentHash(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).take(8).map("%02x".format(_)).mkString
  end computeContentHash

  /** Replace quoted module names and `sourceMappingURL` references throughout `content`.
    *
    * Handles double-quoted, single-quoted, and `./`-prefixed forms as well as source-map URL comments.
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
  end rewriteJsReferences

  /** Build an in-memory map of content-hashed JS files from `outDir`.
    *
    * Algorithm:
    *   1. Read all `.js` files from `outDir`.
    *   2. Parse their `import`/`from` statements to build a dependency graph.
    *   3. Process files in topological order (dependencies first).
    *   4. For each file, rewrite cross-module import references using the hashes computed so far, then hash the
    *      rewritten content. The resulting hashed filename is `<base>.<hash>.js` (hyphens in the base name are replaced
    *      with underscores to match the Mill plugin convention).
    *   5. Non-JS files (source maps, `.wasm`, etc.) are copied verbatim, with source-map filenames renamed to match
    *      their hashed JS counterparts.
    *
    * The returned map uses the **hashed** filename as the key, so the HTTP server can serve any key directly as an
    * immutable resource.
    *
    * Note: files on disk are never modified.
    */
  def buildInMemoryHashedFiles(
      outDir: Path
  )(logger: Scribe[IO]): IO[ConcurrentHashMap[String, Array[Byte]]] =
    for
      _ <- logger.debug(s"[ContentHasher] Building in-memory hashed files from $outDir")

      allFiles <- Files[IO].list(outDir).evalFilter(Files[IO].isRegularFile).compile.toVector

      // Split JS files from everything else (.map, .wasm, …)
      jsFiles = allFiles.filter(p => p.fileName.toString.endsWith(".js"))
      otherFiles = allFiles.filterNot(p => p.fileName.toString.endsWith(".js"))

      // Read all JS files into memory
      jsContentsVec <- jsFiles.traverse {
        f =>
          Files[IO].readAll(f).compile.toVector.map(v => f.fileName.toString -> new String(v.toArray, "UTF-8"))
      }
      jsContents = jsContentsVec.toMap

      // Build dependency graph
      jsFileNames = jsFiles.map(_.fileName.toString).toSet
      fileDeps = jsContents.map {
        case (name, content) =>
          val imported = parseJsImports(content).filter(jsFileNames.contains)
          name -> imported.toSet
      }

      sortedNames = topologicalSort(jsFileNames.toList, fileDeps)

      result = new ConcurrentHashMap[String, Array[Byte]]()
      jsHashMapping = mutable.LinkedHashMap.empty[String, String]

      // Process JS files in topological order
      _ <- sortedNames.traverse {
        name =>
          val content = jsContents(name)
          val rewrittenContent = rewriteJsReferences(content, jsHashMapping.toMap)
          val hash = computeContentHash(rewrittenContent.getBytes("UTF-8"))
          val baseName = name.stripSuffix(".js").replace("-", "_")
          val hashedName = s"$baseName.$hash.js"
          jsHashMapping(name) = hashedName

          // Update sourceMappingURL to point to the renamed map file
          val finalContent = rewrittenContent.replace(
            "sourceMappingURL=" + name + ".map",
            "sourceMappingURL=" + hashedName + ".map"
          )
          IO(result.put(hashedName, finalContent.getBytes("UTF-8"))) >>
            logger.debug(s"[ContentHasher] $name -> $hashedName")
      }

      // Build full mapping including source-map renames
      fullMapping: Map[String, String] = jsHashMapping
        .flatMap {
          case (orig, hashed) =>
            Seq(orig -> hashed, (orig + ".map") -> (hashed + ".map"))
        }
        .toMap

      // Copy non-JS files, renaming source-maps to match their hashed JS file
      _ <- otherFiles.traverse {
        f =>
          Files[IO]
            .readAll(f)
            .compile
            .toVector
            .map {
              bytes =>
                val origName = f.fileName.toString
                val newName = fullMapping.getOrElse(origName, origName)
                result.put(newName, bytes.toArray)
            }
      }

      _ <- logger.debug(
        s"[ContentHasher] In-memory map built: ${scala
            .jdk
            .CollectionConverters
            .SetHasAsScala(result.keySet())
            .asScala
            .mkString(", ")}"
      )
    yield result
  end buildInMemoryHashedFiles

end ContentHasher
