package io.github.quafadas.sjsls

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import fs2.io.file.Files
import fs2.io.file.Path

import scribe.Scribe

import cats.effect.IO
import cats.syntax.all.*

/** Content hashing for Scala.js linker output, for serving with immutable cache headers.
  *
  * Each module is hashed independently and the browser resolves the original module names to hashed ones through an
  * import map, see [[importMapScript]].
  */
object ContentHasher:

  /** Content-hashed linker output.
    *
    * @param files
    *   every output file, with JS modules (and their source maps) renamed to `<base>.<hash>.js`
    * @param importMap
    *   original JS file name -> hashed file name, e.g. `internal-foo.js -> internal_foo.0123456789abcdef.js`
    */
  case class HashedOutput(files: Map[String, Array[Byte]], importMap: Map[String, String])

  /** Compute a short (16 hex character) SHA-256 prefix of the given bytes. */
  def computeContentHash(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).take(8).map("%02x".format(_)).mkString
  end computeContentHash

  /** Hash each JS module on its own content.
    *
    * Import specifiers inside the modules are left untouched (`import "./foo.js"`). The browser is pointed at the
    * hashed files by an import map instead, so a change to one module only changes that module's URL rather than
    * cascading up through every module that imports it.
    *
    * The only rewrite is each module's own `sourceMappingURL`, which follows the module's new name.
    */
  def hashWithImportMap(
      files: Map[String, Array[Byte]],
      hashedBaseName: String => String = _.replace("-", "_")
  ): HashedOutput =
    val importMap = files.collect {
      case (name, bytes) if name.endsWith(".js") =>
        name -> s"${hashedBaseName(name.stripSuffix(".js"))}.${computeContentHash(bytes)}.js"
    }

    val renamed = files.map {
      case (name, bytes) =>
        importMap.get(name) match
          case Some(hashed) =>
            val content =
              new String(bytes, UTF_8).replace(s"sourceMappingURL=$name.map", s"sourceMappingURL=$hashed.map")
            hashed -> content.getBytes(UTF_8)
          case None =>
            importMap.get(name.stripSuffix(".map")) match
              case Some(hashed) if name.endsWith(".js.map") => s"$hashed.map" -> bytes
              case _                                        => name -> bytes
    }

    HashedOutput(renamed, importMap)
  end hashWithImportMap

  /** Read the linker output in `outDir` and content-hash it in memory. Files on disk are never modified.
    *
    * As well as the hashed names, every JS module is also kept under its original name. An HTML page that carries the
    * import map only ever requests the hashed names, which are served as immutable. Anything that doesn't (e.g. a user
    * supplied `index.html` with its own import map) still works through the unhashed names, which are served no-cache.
    */
  def buildInMemoryHashedFiles(outDir: Path)(logger: Scribe[IO]): IO[HashedOutput] =
    for
      _ <- logger.debug(s"[ContentHasher] Building in-memory hashed files from $outDir")
      allFiles <- Files[IO].list(outDir).evalFilter(Files[IO].isRegularFile).compile.toVector
      contents <- allFiles.traverse(f => Files[IO].readAll(f).compile.to(Array).map(f.fileName.toString -> _))
      hashed = hashWithImportMap(contents.toMap)
      aliases = hashed.importMap.map((orig, h) => orig -> hashed.files(h))
      _ <- logger.debug(s"[ContentHasher] import map: ${hashed.importMap.mkString(", ")}")
    yield hashed.copy(files = hashed.files ++ aliases)

end ContentHasher
