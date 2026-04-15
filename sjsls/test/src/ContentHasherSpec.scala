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
  // parseJsImports
  // --------------------------------------------------------------------------

  test("parseJsImports extracts double-quoted relative import") {
    val content = """import { foo } from "./chunk.js";"""
    assertEquals(C.parseJsImports(content), Seq("chunk.js"))
  }

  test("parseJsImports extracts single-quoted relative import") {
    val content = """import { foo } from './chunk.js';"""
    assertEquals(C.parseJsImports(content), Seq("chunk.js"))
  }

  test("parseJsImports handles bare (no ./) imports") {
    val content = """import { foo } from "chunk.js";"""
    assertEquals(C.parseJsImports(content), Seq("chunk.js"))
  }

  test("parseJsImports returns empty for no imports") {
    assertEquals(C.parseJsImports("const x = 1;"), Seq.empty)
  }

  test("parseJsImports extracts multiple imports") {
    val content =
      """|import a from "./a.js";
         |import b from "./b.js";
         |""".stripMargin
    assertEquals(C.parseJsImports(content).toSet, Set("a.js", "b.js"))
  }

  // --------------------------------------------------------------------------
  // rewriteJsReferences
  // --------------------------------------------------------------------------

  test("rewriteJsReferences rewrites double-quoted relative import") {
    val mapping = Map("chunk.js" -> "chunk.abc12345.js")
    val content = """import * as m from "./chunk.js";"""
    assertEquals(C.rewriteJsReferences(content, mapping), """import * as m from "./chunk.abc12345.js";""")
  }

  test("rewriteJsReferences rewrites sourceMappingURL comments") {
    val mapping2 = Map("main.js" -> "main.abc12345.js", "main.js.map" -> "main.abc12345.js.map")
    val content = "//# sourceMappingURL=main.js.map"
    // sourceMappingURL=main.js.map is replaced by sourceMappingURL=main.abc12345.js.map
    assertEquals(C.rewriteJsReferences(content, mapping2), "//# sourceMappingURL=main.abc12345.js.map")
  }

  test("rewriteJsReferences is a no-op when mapping is empty") {
    val content = """import x from "./foo.js";"""
    assertEquals(C.rewriteJsReferences(content, Map.empty), content)
  }

  // --------------------------------------------------------------------------
  // topologicalSort
  // --------------------------------------------------------------------------

  test("topologicalSort puts dependency before dependent") {
    val names = List("main.js", "chunk.js")
    // main.js depends on chunk.js
    val deps = Map("main.js" -> Set("chunk.js"), "chunk.js" -> Set.empty[String])
    val sorted = C.topologicalSort(names, deps)
    assert(sorted.indexOf("chunk.js") < sorted.indexOf("main.js"))
  }

  test("topologicalSort handles no-dependency graph") {
    val names = List("a.js", "b.js", "c.js")
    val deps = Map("a.js" -> Set.empty[String], "b.js" -> Set.empty[String], "c.js" -> Set.empty[String])
    val sorted = C.topologicalSort(names, deps)
    assertEquals(sorted.toSet, names.toSet)
  }

  test("topologicalSort returns all nodes") {
    val names = List("a.js", "b.js")
    val deps = Map("a.js" -> Set("b.js"), "b.js" -> Set.empty[String])
    val sorted = C.topologicalSort(names, deps)
    assertEquals(sorted.toSet, Set("a.js", "b.js"))
  }

  // --------------------------------------------------------------------------
  // buildInMemoryHashedFiles
  // --------------------------------------------------------------------------

  test("buildInMemoryHashedFiles hashes JS files and rewrites imports") {
    val chunkContent = "export const x = 1;"
    val mainContent = """import { x } from "./chunk.js"; console.log(x);"""

    val tempDir = os.temp.dir()
    try
      os.write(tempDir / "chunk.js", chunkContent)
      os.write(tempDir / "main.js", mainContent)

      val result = C
        .buildInMemoryHashedFiles(
          fs2.io.file.Path(tempDir.toString())
        )(scribe.cats[IO])
        .unsafeRunSync()(using cats.effect.unsafe.implicits.global)

      val keys = scala.jdk.CollectionConverters.SetHasAsScala(result.keySet()).asScala.toSet

      // chunk.js must have been hashed to chunk.<hash>.js
      val hashedChunk = keys.find(k => k.startsWith("chunk.") && k.endsWith(".js") && k != "chunk.js")
      assert(hashedChunk.isDefined, s"Expected a hashed chunk.js in keys: $keys")

      // main.js must be present with a hash too
      val hashedMain = keys.find(k => k.startsWith("main.") && k.endsWith(".js") && k != "main.js")
      assert(hashedMain.isDefined, s"Expected a hashed main.js in keys: $keys")

      // main.js must also be present under its original name (HTML references /main.js)
      assert(keys.contains("main.js"), s"main.js should be present as an alias key: $keys")

      // The hashed main content must reference the hashed chunk name
      val mainBytes = result.get(hashedMain.get)
      val mainStr = new String(mainBytes, "UTF-8")
      assert(mainStr.contains(hashedChunk.get), s"main.js content should reference ${hashedChunk.get}: $mainStr")

      // Original (unhashed) name must not be present for internal (non-entry) modules
      assert(!keys.contains("chunk.js"), s"chunk.js should not be a separate key: $keys")
    finally os.remove.all(tempDir)
    end try
  }

  test("buildInMemoryHashedFiles renames source-map files to match hashed JS") {
    val jsContent = "export const y = 2; //# sourceMappingURL=lib.js.map"
    val mapContent = """{"version":3,"sources":["lib.scala"]}"""

    val tempDir = os.temp.dir()
    try
      os.write(tempDir / "lib.js", jsContent)
      os.write(tempDir / "lib.js.map", mapContent)

      val result = C
        .buildInMemoryHashedFiles(
          fs2.io.file.Path(tempDir.toString())
        )(scribe.cats[IO])
        .unsafeRunSync()(using cats.effect.unsafe.implicits.global)

      val keys = scala.jdk.CollectionConverters.SetHasAsScala(result.keySet()).asScala.toSet

      val hashedJs = keys.find(k => k.startsWith("lib.") && k.endsWith(".js") && k != "lib.js")
      val hashedMap = keys.find(k => k.endsWith(".js.map"))
      assert(hashedJs.isDefined, s"Expected hashed lib.js in keys: $keys")
      assert(hashedMap.isDefined, s"Expected a .js.map in keys: $keys")
      // The map should be renamed to match the hashed JS
      assertEquals(hashedMap.get, hashedJs.get + ".map")
    finally os.remove.all(tempDir)
    end try
  }

  test("buildInMemoryHashedFiles replaces hyphens with underscores in base name") {
    val jsContent = "export const z = 3;"

    val tempDir = os.temp.dir()
    try
      os.write(tempDir / "my-module.js", jsContent)

      val result = C
        .buildInMemoryHashedFiles(
          fs2.io.file.Path(tempDir.toString())
        )(scribe.cats[IO])
        .unsafeRunSync()(using cats.effect.unsafe.implicits.global)

      val keys = scala.jdk.CollectionConverters.SetHasAsScala(result.keySet()).asScala.toSet
      assert(
        keys.forall(k => !k.contains("-")),
        s"Hashed names should not contain hyphens: $keys"
      )
      assert(
        keys.exists(k => k.startsWith("my_module.")),
        s"Expected key starting with my_module. in: $keys"
      )
    finally os.remove.all(tempDir)
    end try
  }

  test("buildInMemoryHashedFiles produces immutable-cache-friendly hashed names") {
    val jsContent = "const a = 1;"
    val tempDir = os.temp.dir()
    try
      os.write(tempDir / "app.js", jsContent)

      val result = C
        .buildInMemoryHashedFiles(
          fs2.io.file.Path(tempDir.toString())
        )(scribe.cats[IO])
        .unsafeRunSync()(using cats.effect.unsafe.implicits.global)

      val keys = scala.jdk.CollectionConverters.SetHasAsScala(result.keySet()).asScala.toSet
      assert(
        keys.forall(k => k.matches(".*\\.[a-f0-9]{8,}\\..*")),
        s"All JS keys should be content-hashed: $keys"
      )
    finally os.remove.all(tempDir)
    end try
  }

end ContentHasherSuite
