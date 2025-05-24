package io.github.quafadas.sjsls

import java.time.ZonedDateTime

import org.http4s.Request
import org.http4s.Status
import org.http4s.syntax.literals.uri

import scribe.Level

import cats.effect.IO
import cats.effect.kernel.Ref

import munit.CatsEffectSuite

class SpaRoutesSuite extends CatsEffectSuite:

  val setupRef = ResourceFunFixture {
    for
      logger <- IO(scribe.cats[IO]).toResource
      modules <- Ref[IO].of(Map("main.js" -> "mainHash", "internal-xxxx.js" -> "internalHash")).toResource
      tempDir = os.temp.dir()
      tempFile = tempDir / "index.html"
      _ = os.write(tempFile, """<head><title>Test</title></head><body><h1>Test</h1></body>""")
    yield (logger, modules, tempDir)
  }

  override def beforeAll(): Unit =
    scribe
      .Logger
      .root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(Level.get("info").get))
      .replace()

  setupRef.test("spa routes have preloads injected for the easy case") {
    (logger, modules, _) =>
      val spaRoute = buildSpaRoute(None, modules, ZonedDateTime.now(), true)(logger)
      val resp = spaRoute(Request[IO](uri = uri"/anything")).getOrElse(fail("No response"))
      for
        body <- resp.map(_.bodyText.compile.string)
        _ <- assertIOBoolean(resp.map(_.status == Status.Ok))
        body <- resp.flatMap(_.bodyText.compile.string)
      yield
        assert(body.contains("""<link rel="modulepreload" href="internal-xxxx.js?h=internalHash" />"""))
        assert(!body.contains("mainHash"))
      end for

  }

  setupRef.test("spa routes have preloads injected for the styles case") {
    (logger, modules, _) =>
      val spaRoute = buildSpaRoute(
        Some(IndexHtmlConfig.StylesOnly(fs2.io.file.Path.apply("doestmatter"))),
        modules,
        ZonedDateTime.now(),
        true
      )(logger)
      val resp = spaRoute(Request[IO](uri = uri"/anything")).getOrElse(fail("No response"))
      for
        body <- resp.map(_.bodyText.compile.string)
        _ <- assertIOBoolean(resp.map(_.status == Status.Ok))
        body <- resp.flatMap(_.bodyText.compile.string)
      yield
        assert(body.contains("""<link rel="modulepreload" href="internal-xxxx.js?h=internalHash" />"""))
        assert(!body.contains("""mainHash"""))
      end for

  }

  setupRef.test("spa routes have preloads injected for the index.html case") {
    (logger, modules, tempDir) =>
      val spaRoute = buildSpaRoute(
        Some(IndexHtmlConfig.StylesOnly(fs2.io.file.Path.apply(tempDir.toString))),
        modules,
        ZonedDateTime.now(),
        injectPreloads = true
      )(logger)
      val resp = spaRoute(Request[IO](uri = uri"/anything")).getOrElse(fail("No response"))
      for
        body <- resp.map(_.bodyText.compile.string)
        _ <- assertIOBoolean(resp.map(_.status == Status.Ok))
        body <- resp.flatMap(_.bodyText.compile.string)
      yield
        assert(!body.contains("mainHash"))
        assert(body.contains("""<link rel="modulepreload" href="internal-xxxx.js?h=internalHash" />"""))
      end for

  }

end SpaRoutesSuite
