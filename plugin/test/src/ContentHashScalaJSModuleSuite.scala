package io.github.quafadas

import java.nio.ByteBuffer
import java.security.MessageDigest
import scala.collection.mutable

import mill.PathRef
import mill.scalajslib.ContentHashScalaJSModule
import mill.scalajslib.FileBasedContentHashScalaJSModule
import mill.scalajslib.api.ModuleKind
import mill.scalajslib.api.Report
import munit.FunSuite

/** Unit tests for [[ContentHashScalaJSModule]].
  *
  * These tests exercise the pure helper methods exposed on the companion object (`computeContentHash`,
  * `rewriteJsReferences`, `parseJsImports`, `topologicalSort`, `applyContentHash`) without requiring an actual Scala.js
  * compilation step or any Mill infrastructure.
  */
class ContentHashScalaJSModuleSuite extends FunSuite:

  private val C = ContentHashScalaJSModule

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
  // rewriteJsReferences
  // --------------------------------------------------------------------------

  test("rewriteJsReferences rewrites double-quoted module names") {
    val mapping = Map("main.js" -> "main.abc12345.js")
    val content = """import { foo } from "main.js";"""
    val result = C.rewriteJsReferences(content, mapping)
    assertEquals(result, """import { foo } from "main.abc12345.js";""")
  }

  test("rewriteJsReferences rewrites double-quoted relative-path imports (Scala.js ESModule default)") {
    val mapping = Map("chunk.js" -> "chunk.abc12345.js")
    val content = """import * as m from "./chunk.js";"""
    val result = C.rewriteJsReferences(content, mapping)
    assertEquals(result, """import * as m from "./chunk.abc12345.js";""")
  }

  test("rewriteJsReferences rewrites single-quoted module names") {
    val mapping = Map("chunk.js" -> "chunk.deadbeef.js")
    val content = """const m = import('chunk.js');"""
    val result = C.rewriteJsReferences(content, mapping)
    assertEquals(result, """const m = import('chunk.deadbeef.js');""")
  }

  test("rewriteJsReferences rewrites single-quoted relative-path imports") {
    val mapping = Map("chunk.js" -> "chunk.deadbeef.js")
    val content = """const m = import('./chunk.js');"""
    val result = C.rewriteJsReferences(content, mapping)
    assertEquals(result, """const m = import('./chunk.deadbeef.js');""")
  }

  test("rewriteJsReferences rewrites sourceMappingURL comments") {
    val mapping = Map("main.js" -> "main.abc12345.js", "main.js.map" -> "main.abc12345.js.map")
    val content = "//# sourceMappingURL=main.js.map"
    val result = C.rewriteJsReferences(content, mapping)
    assertEquals(result, "//# sourceMappingURL=main.abc12345.js.map")
  }

  test("rewriteJsReferences handles multiple replacements") {
    val mapping = Map(
      "a.js" -> "a.111.js",
      "a.js.map" -> "a.111.js.map",
      "b.js" -> "b.222.js",
      "b.js.map" -> "b.222.js.map"
    )
    val content =
      """|import x from "a.js";
         |import y from 'b.js';
         |//# sourceMappingURL=a.js.map
         |""".stripMargin
    val result = C.rewriteJsReferences(content, mapping)
    assert(result.contains(""""a.111.js""""), result)
    assert(result.contains("'b.222.js'"), result)
    assert(result.contains("sourceMappingURL=a.111.js.map"), result)
  }

  test("rewriteJsReferences does not replace bare (unquoted) filename occurrences") {
    val mapping = Map("main.js" -> "main.abc.js")
    val content = "// This references main.js in a comment without quotes"
    val result = C.rewriteJsReferences(content, mapping)
    assert(!result.contains("main.abc.js"), result)
  }

  test("rewriteJsReferences leaves content unchanged when mapping is empty") {
    val content = """import foo from "bar.js";"""
    val result = C.rewriteJsReferences(content, Map.empty)
    assertEquals(result, content)
  }

  // --------------------------------------------------------------------------
  // parseJsImports
  // --------------------------------------------------------------------------

  test("parseJsImports extracts ./relative imports (Scala.js ESModule default)") {
    val content = """import * as m from "./chunk.js";"""
    assertEquals(C.parseJsImports(content).toList, List("chunk.js"))
  }

  test("parseJsImports extracts bare (no prefix) imports") {
    val content = """import * as m from "chunk.js";"""
    assertEquals(C.parseJsImports(content).toList, List("chunk.js"))
  }

  test("parseJsImports extracts multiple imports from one file") {
    val content =
      """|import * as a from "./a.js";
         |import * as b from "./b.js";
         |""".stripMargin
    assertEquals(C.parseJsImports(content).toSet, Set("a.js", "b.js"))
  }

  test("parseJsImports returns empty for files without imports") {
    val content = "export const x = 1;\n//# sourceMappingURL=chunk.js.map\n"
    assert(C.parseJsImports(content).isEmpty)
  }

  test("parseJsImports does not match sourceMappingURL") {
    val content = "//# sourceMappingURL=main.js.map"
    assert(C.parseJsImports(content).isEmpty)
  }

  // --------------------------------------------------------------------------
  // topologicalSort
  // --------------------------------------------------------------------------

  test("topologicalSort: leaf comes before the file that imports it") {
    val names = List("main.js", "chunk.js")
    val deps = Map("main.js" -> Set("chunk.js"), "chunk.js" -> Set.empty[String])
    val order = C.topologicalSort(names, deps)
    assert(
      order.indexOf("chunk.js") < order.indexOf("main.js"),
      s"chunk.js should come before main.js, got: $order"
    )
  }

  test("topologicalSort: three-level chain") {
    val names = List("a.js", "b.js", "c.js")
    val deps = Map("a.js" -> Set("b.js"), "b.js" -> Set("c.js"), "c.js" -> Set.empty[String])
    val order = C.topologicalSort(names, deps)
    assert(order.indexOf("c.js") < order.indexOf("b.js"), s"order: $order")
    assert(order.indexOf("b.js") < order.indexOf("a.js"), s"order: $order")
  }

  test("topologicalSort: independent files all appear in result") {
    val names = List("a.js", "b.js")
    val deps = Map("a.js" -> Set.empty[String], "b.js" -> Set.empty[String])
    val order = C.topologicalSort(names, deps)
    assertEquals(order.toSet, Set("a.js", "b.js"))
  }

  // --------------------------------------------------------------------------
  // applyContentHash - file system integration
  // --------------------------------------------------------------------------

  test("applyContentHash renames JS files and rewrites cross-module references") {
    val srcDir = os.temp.dir()
    val destDir = os.temp.dir()

    try
      // Use "./chunk.js" - the actual format Scala.js ESModule output produces.
      val mainJs =
        """import * as chunk from "./chunk.js";
          |//# sourceMappingURL=main.js.map
          |""".stripMargin
      val chunkJs =
        """export const x = 1;
          |//# sourceMappingURL=chunk.js.map
          |""".stripMargin
      val mainJsMap = """{"version":3,"file":"main.js"}"""
      val chunkJsMap = """{"version":3,"file":"chunk.js"}"""

      os.write(srcDir / "main.js", mainJs)
      os.write(srcDir / "chunk.js", chunkJs)
      os.write(srcDir / "main.js.map", mainJsMap)
      os.write(srcDir / "chunk.js.map", chunkJsMap)

      val report = Report(
        publicModules = Seq(
          Report.Module(
            moduleID = "main",
            jsFileName = "main.js",
            sourceMapName = Some("main.js.map"),
            moduleKind = ModuleKind.ESModule
          )
        ),
        dest = PathRef(srcDir)
      )

      val result = C.applyContentHash(report, destDir)

      val files = os.list(destDir).map(_.last).toSet

      assert(!files.contains("main.js"), s"original main.js should be gone: $files")
      assert(!files.contains("chunk.js"), s"original chunk.js should be gone: $files")

      val hashedMainJs = files.find(f => f.startsWith("main.") && f.endsWith(".js"))
      val hashedChunkJs = files.find(f => f.startsWith("chunk.") && f.endsWith(".js"))
      assert(hashedMainJs.isDefined, s"no hashed main.js found in: $files")
      assert(hashedChunkJs.isDefined, s"no hashed chunk.js found in: $files")

      val mainContent = os.read(destDir / hashedMainJs.get)
      val chunkJsName = hashedChunkJs.get

      // main.js must reference the hashed chunk name using the ./prefix style.
      assert(
        mainContent.contains("\"./" + chunkJsName + "\""),
        s"hashed main.js should reference ./$chunkJsName but got:\n$mainContent"
      )

      val updatedModule = result.publicModules.head
      assertEquals(updatedModule.jsFileName, hashedMainJs.get)
      assert(updatedModule.sourceMapName.exists(_.endsWith(".map")))

    finally
      os.remove.all(srcDir)
      os.remove.all(destDir)
    end try
  }

  test("applyContentHash: dependency hash change cascades to importer hash") {
    // If B's content changes, B's hash changes; A imports B so A's rewritten
    // import also changes, meaning A's hash must also change.
    def runWithBContent(bContent: String): (String, String) =
      val srcDir = os.temp.dir()
      val destDir = os.temp.dir()
      try
        os.write(srcDir / "a.js", """import * as b from "./b.js";""")
        os.write(srcDir / "b.js", bContent)
        val report = Report(
          publicModules = Seq(Report.Module("a", "a.js", None, ModuleKind.ESModule)),
          dest = PathRef(srcDir)
        )
        val outFiles = os.list(ContentHashScalaJSModule.applyContentHash(report, destDir).dest.path).map(_.last).toSet
        val hashedA = outFiles.find(f => f.startsWith("a.") && f.endsWith(".js")).get
        val hashedB = outFiles.find(f => f.startsWith("b.") && f.endsWith(".js")).get
        (hashedA, hashedB)
      finally
        os.remove.all(srcDir)
        os.remove.all(destDir)
      end try
    end runWithBContent

    val (a1, b1) = runWithBContent("export const x = 1;")
    val (a2, b2) = runWithBContent("export const x = 2;")

    assertNotEquals(b1, b2, "b hash must change when b content changes")
    assertNotEquals(a1, a2, "a hash must change when imported b hash changes")
  }

  // --------------------------------------------------------------------------
  // applyContentHash - WASM integration
  // --------------------------------------------------------------------------

  test("applyContentHash preserves WASM binary alongside hashed JS") {
    val srcDir = os.temp.dir()
    val destDir = os.temp.dir()

    try
      val mainJs =
        """import { exports } from "./main.wasm";
          |//# sourceMappingURL=main.js.map
          |""".stripMargin
      val wasmMagic = Array[Byte](0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
      val mainJsMap = """{"version":3,"file":"main.js"}"""

      os.write(srcDir / "main.js", mainJs)
      os.write(srcDir / "main.wasm", wasmMagic)
      os.write(srcDir / "main.js.map", mainJsMap)

      val report = Report(
        publicModules = Seq(
          Report.Module(
            moduleID = "main",
            jsFileName = "main.js",
            sourceMapName = Some("main.js.map"),
            moduleKind = ModuleKind.ESModule
          )
        ),
        dest = PathRef(srcDir)
      )

      val result = C.applyContentHash(report, destDir)
      val files = os.list(destDir).map(_.last).toSet

      val hashedMainJs = files.find(f => f.startsWith("main.") && f.endsWith(".js"))
      assert(hashedMainJs.isDefined, s"no hashed main.js found in: $files")
      assert(!files.contains("main.js"), s"original main.js should not be present: $files")
      assert(files.contains("main.wasm"), s"main.wasm should be present: $files")
      assertEquals(os.read.bytes(destDir / "main.wasm").toSeq, wasmMagic.toSeq)
      assertEquals(result.publicModules.head.jsFileName, hashedMainJs.get)

    finally
      os.remove.all(srcDir)
      os.remove.all(destDir)
    end try
  }

  // --------------------------------------------------------------------------
  // Filename sanitisation: "-" → "_"
  // --------------------------------------------------------------------------

  // --------------------------------------------------------------------------
  // In-memory hashing helpers (mirrors InMemoryHashScalaJSModule logic)
  // --------------------------------------------------------------------------

  test("in-memory: MemOutputDirectory round-trips bytes correctly") {
    val dir = MemOutputDirectory()
    val data = "export const x = 1;\n".getBytes("UTF-8")
    dir.put("chunk.js", ByteBuffer.wrap(data))

    val buf = dir.content("chunk.js").get
    val readBack = new Array[Byte](buf.remaining())
    buf.get(readBack)
    assertEquals(new String(readBack, "UTF-8"), "export const x = 1;\n")
  }

  test("in-memory: repeated content() calls each return full bytes") {
    val dir = MemOutputDirectory()
    dir.put("file.js", ByteBuffer.wrap("hello".getBytes("UTF-8")))

    def readStr(): String =
      val buf = dir.content("file.js").get
      val bytes = new Array[Byte](buf.remaining())
      buf.get(bytes)
      new String(bytes, "UTF-8")

    assertEquals(readStr(), "hello")
    assertEquals(readStr(), "hello")
  }

  test("in-memory: hashing workflow with MemOutputDirectory produces sanitised names") {
    val C = ContentHashScalaJSModule

    val dir = MemOutputDirectory()
    dir.put("my-chunk.js", ByteBuffer.wrap("export const y = 2;\n".getBytes("UTF-8")))
    dir.put("my-app.js", ByteBuffer.wrap("""import * as c from "./my-chunk.js";""".getBytes("UTF-8")))

    val jsFileNames = dir.fileNames().filter(_.endsWith(".js")).toSet

    def readStr(name: String): String =
      val buf = dir.content(name).get
      val bytes = new Array[Byte](buf.remaining())
      buf.get(bytes)
      new String(bytes, "UTF-8")

    val fileDeps: Map[String, Set[String]] = jsFileNames.map {
      name =>
        val imported = C.parseJsImports(readStr(name)).filter(jsFileNames.contains)
        (name, imported.toSet)
    }.toMap

    val sortedNames = C.topologicalSort(jsFileNames.toList, fileDeps)
    val jsHashMapping = mutable.LinkedHashMap.empty[String, String]

    sortedNames.foreach {
      name =>
        val content = readStr(name)
        val rewritten = C.rewriteJsReferences(content, jsHashMapping.toMap)
        val hash = C.computeContentHash(rewritten.getBytes("UTF-8"))
        val baseName = name.stripSuffix(".js").replace("-", "_")
        val hashedName = s"$baseName.$hash.js"
        jsHashMapping(name) = hashedName
    }

    // Both entries should be sanitised (no hyphens in the hashed output names).
    jsHashMapping.values.foreach { hashed =>
      assert(!hashed.contains("-"), s"hashed name should not contain hyphen: $hashed")
    }
    assert(jsHashMapping("my-chunk.js").startsWith("my_chunk."))
    assert(jsHashMapping("my-app.js").startsWith("my_app."))

    // The hashed app.js should reference the hashed chunk name (with underscore).
    val hashedChunk = jsHashMapping("my-chunk.js")
    val appContent = C.rewriteJsReferences(readStr("my-app.js"), jsHashMapping.toMap)
    assert(appContent.contains(hashedChunk), s"app content should reference $hashedChunk:\n$appContent")
  }

  test("applyContentHash sanitises hyphenated filenames: my-module.js → my_module.<hash>.js") {
    val srcDir = os.temp.dir()
    val destDir = os.temp.dir()

    try
      os.write(srcDir / "my-module.js", "export const x = 1;\n//# sourceMappingURL=my-module.js.map\n")
      os.write(srcDir / "my-module.js.map", """{"version":3,"file":"my-module.js"}""")

      val report = Report(
        publicModules = Seq(
          Report.Module(
            moduleID = "my-module",
            jsFileName = "my-module.js",
            sourceMapName = Some("my-module.js.map"),
            moduleKind = ModuleKind.ESModule
          )
        ),
        dest = PathRef(srcDir)
      )

      val result = C.applyContentHash(report, destDir)
      val files = os.list(destDir).map(_.last).toSet

      assert(!files.contains("my-module.js"), s"hyphenated original should not exist: $files")
      assert(!files.exists(_.contains("-")), s"no hashed filename should contain a hyphen: $files")

      val sanitised = files.find(f => f.startsWith("my_module.") && f.endsWith(".js") && !f.endsWith(".js.map"))
      assert(sanitised.isDefined, s"sanitised name (my_module.<hash>.js) not found in: $files")

      assertEquals(result.publicModules.head.jsFileName, sanitised.get)
      assert(result.publicModules.head.sourceMapName.exists(_.startsWith("my_module.")))

    finally
      os.remove.all(srcDir)
      os.remove.all(destDir)
    end try
  }

  test("applyContentHash sanitises hyphens in cross-module imports") {
    val srcDir = os.temp.dir()
    val destDir = os.temp.dir()

    try
      os.write(srcDir / "my-chunk.js", "export const y = 2;\n")
      os.write(srcDir / "main.js", """import * as c from "./my-chunk.js";""" + "\n")

      val report = Report(
        publicModules = Seq(Report.Module("main", "main.js", None, ModuleKind.ESModule)),
        dest = PathRef(srcDir)
      )

      C.applyContentHash(report, destDir)
      val files = os.list(destDir).map(_.last).toSet

      assert(!files.exists(_.contains("-")), s"no output file should contain a hyphen: $files")

      val hashedMain = files.find(f => f.startsWith("main.") && f.endsWith(".js")).get
      val mainContent = os.read(destDir / hashedMain)
      assert(!mainContent.contains("my-chunk"), s"main.js should not reference hyphenated name:\n$mainContent")
      assert(mainContent.contains("my_chunk"), s"main.js should reference sanitised name:\n$mainContent")

    finally
      os.remove.all(srcDir)
      os.remove.all(destDir)
    end try
  }

  // --------------------------------------------------------------------------
  // stripContentHash
  // --------------------------------------------------------------------------

  private val FB = FileBasedContentHashScalaJSModule

  test("stripContentHash removes 16-hex-char hash from simple name") {
    assertEquals(FB.stripContentHash("main.a1b2c3d4e5f6a7b8.js"), "main.js")
  }

  test("stripContentHash removes hash from underscore-containing base name") {
    assertEquals(FB.stripContentHash("my_module.a1b2c3d4e5f6a7b8.js"), "my_module.js")
  }

  test("stripContentHash passes through unhashed filenames") {
    assertEquals(FB.stripContentHash("main.js"), "main.js")
  }

  test("stripContentHash passes through names with non-hex second-to-last segment") {
    assertEquals(FB.stripContentHash("main.notahex.js"), "main.notahex.js")
  }

  test("stripContentHash handles .js.map extension correctly") {
    assertEquals(FB.stripContentHash("main.a1b2c3d4e5f6a7b8.js.map"), "main.js.map")
  }

end ContentHashScalaJSModuleSuite
