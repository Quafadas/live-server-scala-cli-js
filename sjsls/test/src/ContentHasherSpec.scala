package io.github.quafadas.sjsls

import java.security.MessageDigest

import cats.effect.IO

import munit.CatsEffectSuite

/** Unit tests for [[ContentHasher]].
  *
  * These tests exercise the pure helper methods and the IO-based `buildInMemoryHashedFiles` function without requiring
  * an actual Scala.js compilation step.
  */
class ContentHasherSuite extends CatsEffectSuite:

  private val C = ContentHasher

  // --------------------------------------------------------------------------
  // computeContentHash
  // --------------------------------------------------------------------------

  test("computeContentHash returns a 16-character hex string") {
    val hash = C.computeContentHash("hello world".getBytes("UTF-8"))
    assertEquals(hash.length, 16)
    assert(hash.forall(c => "0123456789abcdef".contains(c)), s"non-hex chars in: $hash")
  }

  test("computeContentHash is deterministic") {
    val bytes = "deterministic input".getBytes("UTF-8")
    assertEquals(C.computeContentHash(bytes), C.computeContentHash(bytes))
  }

  test("computeContentHash differs for different inputs") {
    val h1 = C.computeContentHash("aaa".getBytes("UTF-8"))
    val h2 = C.computeContentHash("bbb".getBytes("UTF-8"))
    assertNotEquals(h1, h2)
  }

  test("computeContentHash matches first 8 bytes of SHA-256") {
    val input = "test content".getBytes("UTF-8")
    val digest = MessageDigest.getInstance("SHA-256")
    val expected = digest.digest(input).take(8).map("%02x".format(_)).mkString
    assertEquals(C.computeContentHash(input), expected)
  }

  // --------------------------------------------------------------------------
  // hashWithImportMap
  // --------------------------------------------------------------------------

  private def utf8(s: String) = s.getBytes("UTF-8")

  private val chunk = "export const x = 1;"
  private val main = """import { x } from "./chunk.js"; console.log(x);"""

  test("hashWithImportMap maps every JS module to a content-hashed name") {
    val hashed = C.hashWithImportMap(Map("chunk.js" -> utf8(chunk), "main.js" -> utf8(main)))
    assertEquals(hashed.importMap.keySet, Set("chunk.js", "main.js"))
    assertEquals(hashed.files.keySet, hashed.importMap.values.toSet)
    assert(hashed.importMap.values.forall(_.matches(".*\\.[a-f0-9]{16}\\.js")), hashed.importMap)
  }

  test("hashWithImportMap leaves imports between modules untouched") {
    val hashed = C.hashWithImportMap(Map("chunk.js" -> utf8(chunk), "main.js" -> utf8(main)))
    assertEquals(new String(hashed.files(hashed.importMap("main.js")), "UTF-8"), main)
  }

  test("hashWithImportMap: changing a dependency does not change the modules that import it") {
    val before = C.hashWithImportMap(Map("chunk.js" -> utf8(chunk), "main.js" -> utf8(main)))
    val after = C.hashWithImportMap(Map("chunk.js" -> utf8("export const x = 2;"), "main.js" -> utf8(main)))
    assertNotEquals(after.importMap("chunk.js"), before.importMap("chunk.js"))
    assertEquals(after.importMap("main.js"), before.importMap("main.js"))
  }

  test("hashWithImportMap renames source maps and the sourceMappingURL that points at them") {
    val hashed = C.hashWithImportMap(
      Map("lib.js" -> utf8("export const y = 2;\n//# sourceMappingURL=lib.js.map"), "lib.js.map" -> utf8("{}"))
    )
    val hashedJs = hashed.importMap("lib.js")
    assertEquals(hashed.files.keySet, Set(hashedJs, s"$hashedJs.map"))
    assert(new String(hashed.files(hashedJs), "UTF-8").endsWith(s"sourceMappingURL=$hashedJs.map"))
  }

  test("hashWithImportMap passes other files through unchanged") {
    val hashed = C.hashWithImportMap(Map("main.wasm" -> Array[Byte](0, 97, 115, 109)))
    assertEquals(hashed.importMap, Map.empty[String, String])
    assertEquals(hashed.files.keySet, Set("main.wasm"))
  }

  test("hashWithImportMap replaces hyphens with underscores in base name by default") {
    val hashed = C.hashWithImportMap(Map("my-module.js" -> utf8("export const z = 3;")))
    assert(hashed.importMap("my-module.js").startsWith("my_module."), hashed.importMap)
  }

  // --------------------------------------------------------------------------
  // buildInMemoryHashedFiles
  // --------------------------------------------------------------------------

  test("buildInMemoryHashedFiles serves modules under both hashed and original names") {
    val tempDir = os.temp.dir()
    try
      os.write(tempDir / "chunk.js", chunk)
      os.write(tempDir / "main.js", main)

      val hashed = C
        .buildInMemoryHashedFiles(fs2.io.file.Path(tempDir.toString()))(scribe.cats[IO])
        .unsafeRunSync()(using cats.effect.unsafe.implicits.global)

      assertEquals(hashed.files.keySet, Set("chunk.js", "main.js") ++ hashed.importMap.values)
      assertEquals(new String(hashed.files("main.js"), "UTF-8"), main)
    finally os.remove.all(tempDir)
    end try
  }

  // --------------------------------------------------------------------------
  // import map in generated HTML
  // --------------------------------------------------------------------------

  test("importMapScript renders original -> hashed names under the base path") {
    assertEquals(
      importMapScript(Map("main.js" -> "main.0123456789abcdef.js"), "/"),
      """<script type="importmap">{"imports":{"/main.js":"/main.0123456789abcdef.js"}}</script>"""
    )
  }

  test("Generated index.html carries the import map, and loads the hashed entry module") {
    val entries = Map("main.js" -> "main.0123456789abcdef.js", "internal-a.js" -> "internal_a.fedcba9876543210.js")
    for
      ref <- cats.effect.Ref[IO].of(entries)
      html <- vanillaTemplate(false, ref, false, false, Some("/main.js"), None).map(_.render)
    yield
      assert(html.contains(importMapScript(entries, "/")), html)
      assert(html.contains("""<script src="/main.0123456789abcdef.js" type="module">"""), html)
      assert(html.indexOf("importmap") < html.indexOf("type=\"module\""), "import map must precede module scripts")
    end for
  }

  test("No import map is rendered for unhashed (on disk) output") {
    for
      ref <- cats.effect.Ref[IO].of(Map("main.js" -> "d41d8cd98f00b204e9800998ecf8427e"))
      html <- vanillaTemplate(false, ref, false, false, Some("/main.js"), None).map(_.render)
    yield
      assert(!html.contains("importmap"), html)
      assert(html.contains("""<script src="/main.js" type="module">"""), html)
    end for
  }

end ContentHasherSuite
