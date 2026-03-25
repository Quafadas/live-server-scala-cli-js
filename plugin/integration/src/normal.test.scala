package io.github.quafadas.millSite

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import mill.scalajslib.api.ModuleSplitStyle
import utest.*

object WebAppModuleTests extends TestSuite:
  def tests: Tests = Tests {
    test("ScalaJsWebAppModule siteGen generates HTML with hashed script references") {
      object build extends TestRootModule with io.github.quafadas.ScalaJsWebAppModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")

        override def mvnDeps = Seq(
          mvn"com.raquo::laminar::17.0.0"
        )

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      UnitTester(build, resourceFolder / "simple").scoped { eval =>
        val Right(result) = eval(build.siteGen).runtimeChecked
        val (siteDirStr, jsDirStr) = result.value
        val siteDir = os.Path(siteDirStr)
        val jsDir = os.Path(jsDirStr)

        // index.html must be present in the siteGen output directory
        assert(os.exists(siteDir / "index.html"))
        val html = os.read(siteDir / "index.html")

        // 1. Must NOT reference the static unhashed name
        assert(!html.contains("src=\"/main.js\""))

        // 2. Extract all <script src="..."> references from the HTML
        val scriptSrcPattern = """src="/([^"]+\.js)"""".r
        val scriptRefs = scriptSrcPattern.findAllMatchIn(html).map(_.group(1)).toList
        if scriptRefs.isEmpty then
          throw new java.lang.AssertionError(s"No <script src=...> found in index.html:\n$html")

        // 3. Every referenced JS file must exist in the fastLinkJS output directory
        val jsOutputFiles = os.list(jsDir).map(_.last).toSet
        scriptRefs.foreach { ref =>
          if !jsOutputFiles.contains(ref) then
            throw new java.lang.AssertionError(
              s"HTML references '$ref' but it is not present in JS output: ${jsOutputFiles.mkString(", ")}"
            )
        }

        // 4. Referenced filenames must be content-hashed (e.g. "main.abc12345.js", not "main.js")
        scriptRefs.foreach { ref =>
          val parts = ref.stripSuffix(".js").split('.')
          if parts.length < 2 then
            throw new java.lang.AssertionError(
              s"Script reference '$ref' does not appear to be a content-hashed filename"
            )
        }

        // 5. The SSE live-reload script must be present
        if !html.contains("/refresh/v1/sse") then
          throw new java.lang.AssertionError("index.html is missing the SSE live-reload script")

        // 6. The app root div must be present
        if !html.contains("id=\"app\"") then
          throw new java.lang.AssertionError("index.html is missing the app root div")
      }
    }

    test("siteGen succeeds and produces index.html when assets directory does not exist") {
      // The 'simple' resource folder has no assets/ subdirectory, so assetsDir
      // resolves to a non-existent path — exercises the optional-assets guard.
      object build extends TestRootModule with io.github.quafadas.ScalaJsWebAppModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")

        override def mvnDeps = Seq(
          mvn"com.raquo::laminar::17.0.0"
        )

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      UnitTester(build, resourceFolder / "simple").scoped { eval =>
        val Right(result) = eval(build.siteGen).runtimeChecked
        val (siteDirStr, _) = result.value
        val siteDir = os.Path(siteDirStr)
        assert(os.exists(siteDir / "index.html"))
        val outputFiles = os.list(siteDir).map(_.last).toSet
        if outputFiles != Set("index.html") then
          throw new java.lang.AssertionError(s"only index.html expected, got: $outputFiles")
      }
    }

    test("siteGen copies assets into site directory when assets directory exists") {
      val assetsTempDir = os.temp.dir()
      os.write(assetsTempDir / "logo.svg", "<svg/>")
      os.makeDir(assetsTempDir / "fonts")
      os.write(assetsTempDir / "fonts" / "font.woff2", "fake-font")

      object build extends TestRootModule with io.github.quafadas.ScalaJsWebAppModule:
        override def scalaVersion: Simple[String] = "3.8.2"
        override def moduleSplitStyle: Simple[ModuleSplitStyle] =
          ModuleSplitStyle.SmallModulesFor("webapp")

        override def mvnDeps = Seq(
          mvn"com.raquo::laminar::17.0.0"
        )

        override def assetsDir = assetsTempDir

        lazy val millDiscover = Discover[this.type]
      end build

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

      try
        UnitTester(build, resourceFolder / "simple").scoped { eval =>
          val Right(result) = eval(build.siteGen).runtimeChecked
          val (siteDirStr, _) = result.value
          val siteDir = os.Path(siteDirStr)
          assert(os.exists(siteDir / "index.html"))
          if !os.exists(siteDir / "logo.svg") then
            throw new java.lang.AssertionError("logo.svg must be copied from assets")
          if !os.exists(siteDir / "fonts" / "font.woff2") then
            throw new java.lang.AssertionError("nested font.woff2 must be copied from assets")
        }
      finally os.remove.all(assetsTempDir)
    }
  }
end WebAppModuleTests
