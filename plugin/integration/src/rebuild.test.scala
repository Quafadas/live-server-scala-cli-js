package io.github.quafadas.sjsls

import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import mill.api.Discover
import mill.api.Task.Simple
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import mill.javalib.DepSyntax
import mill.scalajslib.api.ModuleSplitStyle
import utest.*

/** A second build, after a source edit, must reach the server and the browser.
  *
  * The linker writes into memory, which Mill can't see. If the link task's result doesn't reflect that output, Mill
  * treats everything after the link as cached, and the server keeps serving the first build.
  */
object RebuildTests extends TestSuite:

  private val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

  private def fail(msg: String): Nothing = throw new java.lang.AssertionError(msg)

  /** Change the text the `simple` app renders, from "Hello " to "Bye ". */
  private def editSource(moduleDir: os.Path): Unit =
    val src = moduleDir / "src" / "hi.scala"
    val before = os.read(src)
    val after = before.replace("s\"Hello \"", "s\"Bye \"")
    if after == before then fail(s"$src doesn't contain the text this test edits")
    end if
    os.write.over(src, after)
  end editSource

  private def importMapOf(html: String): Map[String, String] =
    val json = """<script type="importmap">(.*?)</script>"""
      .r
      .findFirstMatchIn(html)
      .getOrElse(fail(s"no import map in index.html:\n$html"))
      .group(1)
    val parsed = upickle.default.read[Map[String, Map[String, String]]](json)
    parsed("imports")
  end importMapOf

  def tests: Tests = Tests {
    test("ScalaJsWebAppModule serves the new code, a new import map and a page refresh after a source edit") {
      object build extends TestRootModule with ScalaJsWebAppModule:
        override def scalaVersion: Simple[String] = V2.scalaVersion
        override def moduleSplitStyle: Simple[ModuleSplitStyle] = ModuleSplitStyle.SmallModulesFor("webapp")
        override def mvnDeps = Seq(mvn"com.raquo::laminar::${V2.laminarVersion}")
        lazy val millDiscover = Discover[this.type]
      end build

      UnitTester(build, resourceFolder / "simple").scoped {
        eval =>
          def siteHtml(): String =
            val Right(result) = eval(build.siteGen).runtimeChecked
            os.read(os.Path(result.value._1) / "index.html")
          end siteHtml

          def servedJs(): Map[String, String] =
            build
              .hashedOutputFiles
              .asScala
              .collect { case (name, bytes) if name.endsWith(".js") => name -> new String(bytes, "UTF-8") }
              .toMap

          val firstHtml = siteHtml()
          if !servedJs().values.exists(_.contains("Hello ")) then fail("the first build doesn't serve the app's text")
          end if

          // Count the page refreshes the second build sends to connected browsers.
          val refreshes = new AtomicInteger(0)
          val (events, unsubscribe) = build.updateServer.subscribeAwait(10).allocated.unsafeRunSync()
          val stopCounting = events.evalMap(_ => IO(refreshes.incrementAndGet())).compile.drain.unsafeRunCancelable()

          try
            editSource(build.moduleDir)
            val secondHtml = siteHtml()
            val served = servedJs()

            if !served.values.exists(_.contains("Bye ")) then
              fail("after the edit the server still doesn't serve the new code")
            end if
            if served.values.exists(_.contains("Hello ")) then
              fail("after the edit the server still serves the old code")
            end if

            val firstMap = importMapOf(firstHtml)
            val secondMap = importMapOf(secondHtml)
            if secondMap == firstMap then fail(s"index.html kept the first build's import map: $firstMap")
            end if
            secondMap
              .values
              .map(_.stripPrefix("./"))
              .foreach {
                target =>
                  if !served.contains(target) then
                    fail(s"import map points at $target, which isn't served. Served: ${served.keys.mkString(", ")}")
              }

            val deadline = System.nanoTime() + 5_000_000_000L
            while refreshes.get() == 0 && System.nanoTime() < deadline do Thread.sleep(50)
            end while
            if refreshes.get() == 0 then fail("the second build didn't send a page refresh")
            end if
          finally
            stopCounting()
            unsubscribe.unsafeRunSync()
          end try
      }
    }

    test("assembleSite reflects a source edit") {
      object build extends TestRootModule with ScalaJsWebAppModule:
        override def scalaVersion: Simple[String] = V2.scalaVersion
        override def moduleSplitStyle: Simple[ModuleSplitStyle] = ModuleSplitStyle.SmallModulesFor("webapp")
        // Keep the app's text readable in the output, and don't depend on terser being installed
        override def scalaJSMinify = false
        override def mvnDeps = Seq(mvn"com.raquo::laminar::${V2.laminarVersion}")
        lazy val millDiscover = Discover[this.type]
      end build

      UnitTester(build, resourceFolder / "simple").scoped {
        eval =>
          def siteJs(): Seq[String] =
            val Right(result) = eval(build.assembleSite).runtimeChecked
            os.list(result.value.path).filter(_.ext == "js").map(os.read(_))
          end siteJs

          if !siteJs().exists(_.contains("Hello ")) then fail("the first site doesn't contain the app's text")
          end if

          editSource(build.moduleDir)
          val js = siteJs()
          if !js.exists(_.contains("Bye ")) then fail("after the edit the site still doesn't contain the new code")
          end if
          if js.exists(_.contains("Hello ")) then fail("after the edit the site still contains the old code")
          end if
      }
    }
  }
end RebuildTests
