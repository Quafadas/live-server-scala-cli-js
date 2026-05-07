package io.github.quafadas.sjsls

import cats.effect.IO

import fs2.io.file.Files
import fs2.io.file.Path

import munit.CatsEffectSuite

class ScaffoldSpec extends CatsEffectSuite:

  given files: Files[IO] = Files.forAsync[IO]

  private val tmpFixture = ResourceFunFixture(Files[IO].tempDirectory)

  tmpFixture.test("scaffoldProject creates the expected directory layout") {
    tmpDir =>
      for
        exitCode <- scaffoldProject("myapp", Some(tmpDir))
        projDir = tmpDir / "myapp"
        assetsDir = projDir / "assets"
        importMapExists <- files.exists(projDir / "importMap.json")
        projectScalaExists <- files.exists(projDir / "project.scala")
        indexLessExists <- files.exists(assetsDir / "index.less")
      yield
        assertEquals(exitCode, cats.effect.ExitCode.Success)
        assert(importMapExists, "importMap.json was not created")
        assert(projectScalaExists, "project.scala was not created")
        assert(indexLessExists, "assets/index.less was not created")
  }

  tmpFixture.test("scaffolded importMap.json contains the ms import") {
    tmpDir =>
      for
        _ <- scaffoldProject("myapp", Some(tmpDir))
        content <- files.readAll(tmpDir / "myapp" / "importMap.json").through(fs2.text.utf8.decode).compile.string
      yield
        assert(content.contains("@ms@2.1.3/+esm"), "importMap.json should contain the ms CDN import")
        assert(content.contains("cdn.jsdelivr.net"), "importMap.json should reference jsDelivr CDN")
  }

  tmpFixture.test("scaffolded project.scala contains expected Scala CLI directives") {
    tmpDir =>
      for
        _ <- scaffoldProject("myapp", Some(tmpDir))
        content <- files.readAll(tmpDir / "myapp" / "project.scala").through(fs2.text.utf8.decode).compile.string
      yield
        assert(content.contains("using platform js"), "project.scala should declare JS platform")
        assert(content.contains("using dep com.raquo::laminar"), "project.scala should declare Laminar dependency")
        assert(content.contains("using jsModuleKind es"), "project.scala should use ES module kind")
        assert(
          content.contains("using jsEsModuleImportMap importMap.json"),
          "project.scala should reference importMap.json"
        )
        assert(content.contains("renderOnDomContentLoaded"), "project.scala should include a Laminar entry point")
  }

  tmpFixture.test("scaffolded assets/index.less contains LESS variables and styles") {
    tmpDir =>
      for
        _ <- scaffoldProject("myapp", Some(tmpDir))
        content <- files
          .readAll(tmpDir / "myapp" / "assets" / "index.less")
          .through(fs2.text.utf8.decode)
          .compile
          .string
      yield
        assert(content.contains("@accent"), "index.less should define @accent variable")
        assert(content.contains("#app"), "index.less should style the #app element")
  }

end ScaffoldSpec
