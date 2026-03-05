package io.github.quafadas

import java.security.MessageDigest

import mill.PathRef
import mill.scalajslib.api.ModuleKind
import mill.scalajslib.api.Report
import munit.FunSuite

/** Unit tests for [[ContentHashScalaJSModule]].
  *
  * These tests exercise the pure helper methods exposed on the companion
  * object (`computeContentHash`, `rewriteJsReferences`) without requiring an
  * actual Scala.js compilation step or any Mill infrastructure.
  */
class ContentHashScalaJSModuleSuite extends FunSuite:

  private val C = ContentHashScalaJSModule

  // -------------------------------------------------------------------------
  // computeContentHash
  // -------------------------------------------------------------------------

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
    val input    = "test content".getBytes("UTF-8")
    val digest   = MessageDigest.getInstance("SHA-256")
    val expected = digest.digest(input).take(8).map("%02x".format(_)).mkString
    assertEquals(C.computeContentHash(input), expected)
  }

  // -------------------------------------------------------------------------
  // rewriteJsReferences – double-quoted imports
  // -------------------------------------------------------------------------

  test("rewriteJsReferences rewrites double-quoted module names") {
    val mapping = Map("main.js" -> "main-abc12345.js")
    val content = """import { foo } from "main.js";"""
    val result  = C.rewriteJsReferences(content, mapping)
    assertEquals(result, """import { foo } from "main-abc12345.js";""")
  }

  // -------------------------------------------------------------------------
  // rewriteJsReferences – single-quoted imports
  // -------------------------------------------------------------------------

  test("rewriteJsReferences rewrites single-quoted module names") {
    val mapping = Map("chunk.js" -> "chunk-deadbeef.js")
    val content = """const m = import('chunk.js');"""
    val result  = C.rewriteJsReferences(content, mapping)
    assertEquals(result, """const m = import('chunk-deadbeef.js');""")
  }

  // -------------------------------------------------------------------------
  // rewriteJsReferences – sourceMappingURL
  // -------------------------------------------------------------------------

  test("rewriteJsReferences rewrites sourceMappingURL comments") {
    val mapping = Map("main.js" -> "main-abc12345.js", "main.js.map" -> "main-abc12345.js.map")
    val content = "//# sourceMappingURL=main.js.map"
    val result  = C.rewriteJsReferences(content, mapping)
    assertEquals(result, "//# sourceMappingURL=main-abc12345.js.map")
  }

  // -------------------------------------------------------------------------
  // rewriteJsReferences – multiple replacements in one file
  // -------------------------------------------------------------------------

  test("rewriteJsReferences handles multiple replacements") {
    val mapping = Map(
      "a.js"     -> "a-111.js",
      "a.js.map" -> "a-111.js.map",
      "b.js"     -> "b-222.js",
      "b.js.map" -> "b-222.js.map"
    )
    val content =
      """|import x from "a.js";
         |import y from 'b.js';
         |//# sourceMappingURL=a.js.map
         |""".stripMargin
    val result = C.rewriteJsReferences(content, mapping)
    assert(result.contains(""""a-111.js""""), result)
    assert(result.contains("'b-222.js'"), result)
    assert(result.contains("sourceMappingURL=a-111.js.map"), result)
  }

  // -------------------------------------------------------------------------
  // rewriteJsReferences – no false positives for bare (unquoted) occurrences
  // -------------------------------------------------------------------------

  test("rewriteJsReferences does not replace bare (unquoted) filename occurrences") {
    val mapping = Map("main.js" -> "main-abc.js")
    // The filename appears un-quoted; the rewriter only touches quoted strings
    // and sourceMappingURL= patterns, so this must remain unchanged.
    val content = "// This references main.js in a comment without quotes"
    val result  = C.rewriteJsReferences(content, mapping)
    assert(!result.contains("main-abc.js"), result)
  }

  test("rewriteJsReferences leaves content unchanged when mapping is empty") {
    val content = """import foo from "bar.js";"""
    val result  = C.rewriteJsReferences(content, Map.empty)
    assertEquals(result, content)
  }

  // -------------------------------------------------------------------------
  // applyContentHash – file system integration
  // -------------------------------------------------------------------------

  test("applyContentHash renames JS files and rewrites cross-module references") {
    val srcDir  = os.temp.dir()
    val destDir = os.temp.dir()

    try
      // Simulate two linked Scala.js modules that reference each other.
      val mainJs = """import * as chunk from "chunk.js";
//# sourceMappingURL=main.js.map
"""
      val chunkJs    = """export const x = 1;
//# sourceMappingURL=chunk.js.map
"""
      val mainJsMap  = """{"version":3,"file":"main.js"}"""
      val chunkJsMap = """{"version":3,"file":"chunk.js"}"""

      os.write(srcDir / "main.js", mainJs)
      os.write(srcDir / "chunk.js", chunkJs)
      os.write(srcDir / "main.js.map", mainJsMap)
      os.write(srcDir / "chunk.js.map", chunkJsMap)

      // Build a minimal Report pointing at srcDir.
      val report = Report(
        publicModules = Seq(
          Report.Module(
            moduleID      = "main",
            jsFileName    = "main.js",
            sourceMapName = Some("main.js.map"),
            moduleKind    = ModuleKind.ESModule
          )
        ),
        dest = PathRef(srcDir)
      )

      val result = C.applyContentHash(report, destDir)

      // --- file names --------------------------------------------------
      val files = os.list(destDir).map(_.last).toSet

      // The hashed JS and map files should be present; originals should not.
      assert(!files.contains("main.js"), s"original main.js should be gone: $files")
      assert(!files.contains("chunk.js"), s"original chunk.js should be gone: $files")

      val hashedMainJs = files.find(f => f.startsWith("main-") && f.endsWith(".js"))
      assert(hashedMainJs.isDefined, s"no hashed main.js found in: $files")

      val hashedChunkJs = files.find(f => f.startsWith("chunk-") && f.endsWith(".js") && !f.endsWith(".map"))
      assert(hashedChunkJs.isDefined, s"no hashed chunk.js found in: $files")

      // --- content -----------------------------------------------------
      val mainContent  = os.read(destDir / hashedMainJs.get)
      val chunkJsName  = hashedChunkJs.get

      // The hashed main.js should reference the hashed chunk name.
      assert(
        mainContent.contains(s""""$chunkJsName""""),
        s"hashed main.js should reference $chunkJsName but got:\n$mainContent"
      )

      // --- Report ------------------------------------------------------
      val updatedModule = result.publicModules.head
      assertEquals(updatedModule.jsFileName, hashedMainJs.get)
      assert(updatedModule.sourceMapName.exists(_.endsWith(".map")))

    finally
      os.remove.all(srcDir)
      os.remove.all(destDir)
    end try
  }

end ContentHashScalaJSModuleSuite
