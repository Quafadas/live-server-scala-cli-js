import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import scala.concurrent.duration.*

import org.http4s.HttpRoutes
import org.typelevel.ci.CIStringSyntax

import fs2.concurrent.Topic
import fs2.io.file.Path

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.MapRef

import munit.CatsEffectSuite
import fs2.io.file.Files
import scribe.Level

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.ci.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.client.Client
import cats.effect.unsafe.IORuntime
import scala.concurrent.duration.*
import cats.effect.std.Random
import fs2.Stream
import cats.effect.std.Console
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.http4s.server.middleware.Logger
import org.http4s.server.middleware.ErrorAction
import scribe.Scribe

class RoutesSuite extends CatsEffectSuite:

  val md = MessageDigest.getInstance("MD5")
  val testStr = "const hi = 'Hello, world'"
  val testHash = md.digest(testStr.getBytes()).map("%02x".format(_)).mkString
  given filesInstance: Files[IO] = Files.forAsync[IO]

  val files = FunFixture[os.Path](
    setup = test =>
      // create a temp folder
      val tempDir = os.temp.dir()
      // create a file in the folder
      val tempFile = tempDir / "test.js"
      os.write(tempFile, testStr)
      os.write(tempDir / "test2.js", testStr)
      os.write(tempDir / "test3.js", testStr)
      tempDir
    ,
    teardown = tempDir =>
      // Always gets called, even if test failed.
      os.remove.all(tempDir)
  )

  val externalIndexHtml = FunFixture[os.Path](
    setup = test =>
      // create a temp folder
      val tempDir = os.temp.dir()
      // create a file in the folder
      val tempFile = tempDir / "index.html"
      os.write(tempFile, """<head><title>Test</title></head><body><h1>Test</h1></body>""")
      os.write(tempDir / "index.less", testStr)
      tempDir
    ,
    teardown = tempDir =>
      // Always gets called, even if test failed.
      os.remove.all(tempDir)
  )

  val externalSyles = FunFixture[os.Path](
    setup = test =>
      val tempDir = os.temp.dir()
      os.write(tempDir / "index.less", testStr)
      tempDir
    ,
    teardown = tempDir =>
      // Always gets called, even if test failed.
      os.remove.all(tempDir)
  )

  files.test("seed map puts files in the map on start") {
    tempDir =>
      for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        _ <- updateMapRef(tempDir.toFs2, fileToHashRef)(logger).toResource
      yield fileToHashRef
        .get
        .map {
          map =>
            assertEquals(map.size, 3)
            assertEquals(map.get("test.js"), Some(testHash))
        }

  }

  files.test("watched map is updated") {
    tempDir =>
      val toCheck = for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        linkingTopic <- Topic[IO, Unit].toResource
        refreshTopic <- Topic[IO, Unit].toResource
        _ <- fileWatcher(fs2.io.file.Path(tempDir.toString), fileToHashRef, linkingTopic, refreshTopic)(logger)
        _ <- IO.sleep(100.millis).toResource // wait for watcher to start
        _ <- updateMapRef(tempDir.toFs2, fileToHashRef)(logger).toResource
        _ <- IO.blocking(os.write.over(tempDir / "test.js", "const hi = 'bye, world'")).toResource
        _ <- linkingTopic.publish1(()).toResource
        _ <- refreshTopic.subscribe(1).head.compile.resource.drain
        oldHash <- fileToHashRef.get.map(_("test.js")).toResource
        _ <- IO.blocking(os.write.over(tempDir / "test.js", "const hi = 'modified, workd'")).toResource
        _ <- linkingTopic.publish1(()).toResource
        _ <- refreshTopic.subscribe(1).head.compile.resource.drain
        newHash <- fileToHashRef.get.map(_("test.js")).toResource
      yield oldHash -> newHash

      toCheck.use {
        case (oldHash, newHash) =>
          IO(assertNotEquals(oldHash, newHash)) >>
            IO(assertEquals(oldHash, "27b2d040a66fb938f134c4b66fb7e9ce")) >>
            IO(assertEquals(newHash, "3ebb82d4d6236c6bfbb90d65943b3e3d"))
      }

  }

  files.test(
    "That the routes serve files on first call with a 200, that the eTag is set, and on second call with a 304"
  ) {
    tempDir =>

      scribe
        .Logger
        .root
        .clearHandlers()
        .clearModifiers()
        .withHandler(minimumLevel = Some(Level.get("error").get))
        .replace()

      val aLogger = scribe.cats[IO]

      def errorActionFor(service: HttpRoutes[IO], logger: Scribe[IO]) = ErrorAction.httpRoutes[IO](
        service,
        (req, thr) =>
          logger.trace(req.toString()) >>
            logger.error(thr)
      )

      val app: Resource[IO, HttpApp[IO]] = for
        logger <- IO(
          aLogger
        ).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        _ <- updateMapRef(tempDir.toFs2, fileToHashRef)(logger).toResource
        refreshPub <- Topic[IO, Unit].toResource
        theseRoutes: HttpRoutes[IO] <- routes(
          tempDir.toString,
          refreshPub,
          None,
          HttpRoutes.empty[IO],
          fileToHashRef,
          Some("app")
        )(logger)
      yield errorActionFor(theseRoutes, aLogger).orNotFound

      app.use {
        (served: HttpApp[IO]) =>
          val client = Client.fromHttpApp(served)
          val request = Request[IO](uri = uri"/test.js")

          val checkResp1 = client
            .run(request)
            .use {
              response =>
                assertEquals(response.status.code, 200)
                assertEquals(response.headers.get(ci"ETag").isDefined, true)
                assertEquals(response.headers.get(ci"ETag").get.head.value, testHash)
                IO.unit
            }

          val request2 = org
            .http4s
            .Request[IO](uri = org.http4s.Uri.unsafeFromString("/test.js"))
            .withHeaders(
              org.http4s.Headers.of(org.http4s.Header.Raw(ci"If-None-Match", testHash))
            )

          val checkResp2 = client
            .run(request2)
            .use {
              resp2 =>
                assertEquals(resp2.status.code, 304)
                IO.unit
            }

          val requestSpaRoute = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("app/anything/random"))
          val checkRespSpa = client
            .run(requestSpaRoute)
            .use {
              resp3 =>
                assertEquals(resp3.status.code, 200)
                assertIOBoolean(resp3.bodyText.compile.string.map(_.contains("src=\"main.js"))) >>
                  IO.unit
            }

          checkResp1 >> checkResp2 >> checkRespSpa

      }
  }

  FunFixture
    .map2(files, externalIndexHtml)
    .test("The files configured externally are served") {
      (appDir, staticDir) =>
        val app = for
          logger <- IO(scribe.cats[IO]).toResource
          fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
          fileToHashMapRef = MapRef.fromSingleImmutableMapRef[IO, String, String](fileToHashRef)
          refreshPub <- Topic[IO, Unit].toResource
          theseRoutes <- routes(
            appDir.toString,
            refreshPub,
            Some(IndexHtmlConfig.IndexHtmlPath(staticDir.toFs2)),
            HttpRoutes.empty[IO],
            fileToHashRef,
            None
          )(logger)
        yield theseRoutes.orNotFound

        app.use {
          served =>
            val requestHtml = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.html"))
            val requestLess = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.less"))

            val responseHtml = served(requestHtml)
            val responseLess = served(requestLess)

            assertIO(responseHtml.map(_.status.code), 200) >>
              assertIO(responseLess.map(_.status.code), 200)
        }
    }

  files.test("That we generate an index.html in the absence of config") {
    appDir =>
      val app = for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        fileToHashMapRef = MapRef.fromSingleImmutableMapRef[IO, String, String](fileToHashRef)
        refreshPub <- Topic[IO, Unit].toResource
        theseRoutes <- routes(
          appDir.toString,
          refreshPub,
          None,
          HttpRoutes.empty[IO],
          fileToHashRef,
          None
        )(logger)
      yield theseRoutes.orNotFound

      app.use {
        served =>
          val requestHtml = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.html"))
          val requestRoot = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/"))

          val responseHtml = served(requestHtml)
          val responseRoot = served(requestRoot)
          assertIO(responseHtml.map(_.status.code), 200) >>
            assertIO(responseRoot.map(_.status.code), 200)
      }
  }

  FunFixture
    .map2(files, externalSyles)
    .test("That index.html and index.less is served with style config") {
      (appDir, styleDir) =>
        val app = for
          logger <- IO(scribe.cats[IO]).toResource
          fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
          fileToHashMapRef = MapRef.fromSingleImmutableMapRef[IO, String, String](fileToHashRef)
          refreshPub <- Topic[IO, Unit].toResource
          theseRoutes <- routes(
            appDir.toString,
            refreshPub,
            Some(IndexHtmlConfig.StylesOnly(styleDir.toFs2)),
            HttpRoutes.empty[IO],
            fileToHashRef,
            None
          )(logger)
        yield theseRoutes.orNotFound

        app.use {
          served =>
            val requestHtml = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.html"))
            val requestLess = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.less"))

            val responseHtml = served(requestHtml)
            val responseLess = served(requestLess)

            assertIO(responseHtml.map(_.status.code), 200) >>
              assertIO(responseLess.map(_.status.code), 200)
        }
    }

  externalIndexHtml.test("Static files are updated when needed") {
    staticDir =>
      def cacheFormatTime = fileLastModified((staticDir / "index.html").toFs2).map {
        seconds =>
          httpCacheFormat(ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("GMT")))
      }

      val app = for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        fileToHashMapRef = MapRef.fromSingleImmutableMapRef[IO, String, String](fileToHashRef)
        refreshPub <- Topic[IO, Unit].toResource
        // subscriber = refreshPub.subscribe(10).take(5).compile.toList
        theseRoutes <- routes(
          os.temp.dir().toString,
          refreshPub,
          Some(IndexHtmlConfig.IndexHtmlPath(staticDir.toFs2)),
          HttpRoutes.empty[IO],
          fileToHashRef,
          None
        )(logger)
      yield (theseRoutes.orNotFound, logger)

      app
        .both(cacheFormatTime.toResource)
        .use {
          case ((served, logger), firstModified) =>
            val request1 = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.html"))

            val request2 = org
              .http4s
              .Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.html"))
              .withHeaders(
                org.http4s.Headers.of(org.http4s.Header.Raw(ci"If-Modified-Since", firstModified.toString()))
              )

            served(request1).flatTap(r => logger.debug("headers" + r.headers.headers.mkString(","))) >>
              logger.trace("first modified " + firstModified) >>
              // You need these ... otherwise no caching.
              // https://simonhearne.com/2022/caching-header-best-practices/
              assertIOBoolean(served(request1).map(_.headers.get(ci"ETag").isDefined)) >>
              assertIOBoolean(served(request1).map(_.headers.get(ci"Cache-Control").isDefined)) >>
              assertIOBoolean(served(request1).map(_.headers.get(ci"Expires").isDefined)) >>
              assertIOBoolean(served(request1).map(_.headers.get(ci"Last-Modified").isDefined)) >>
              // Don't forget to set them _all_
              assertIO(served(request1).map(_.status.code), 200) >>
              assertIO(served(request2).map(_.status.code), 304) >>
              IO.sleep(
                1500.millis
              ) >> // have to wait at least one second otherwish last modified could be the same, if test took <1 sceond to get to this point
              IO.blocking(os.write.over(staticDir / "index.html", """<head><title>Test</title></head>""")) >>
              served(request2).flatMap(_.bodyText.compile.string).flatMap(s => logger.trace(s)) >>
              assertIO(served(request2).map(_.status.code), 200)

        }
  }

  externalIndexHtml.test("Client SPA routes return index.html") {
    staticDir =>
      def cacheFormatTime = fileLastModified((staticDir / "index.html").toFs2).map {
        seconds =>
          httpCacheFormat(ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("GMT")))
      }

      val app = for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        fileToHashMapRef = MapRef.fromSingleImmutableMapRef[IO, String, String](fileToHashRef)
        refreshPub <- Topic[IO, Unit].toResource
        theseRoutes <- routes(
          os.temp.dir().toString,
          refreshPub,
          Some(IndexHtmlConfig.IndexHtmlPath(staticDir.toFs2)),
          HttpRoutes.empty[IO],
          fileToHashRef,
          Some("app")
        )(logger)
      yield (theseRoutes.orNotFound, logger)

      app
        .both(cacheFormatTime.toResource)
        .use {
          case ((served, logger), firstModified) =>
            val request1 = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("app/whocare"))

            val request2 = org
              .http4s
              .Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.html"))
              .withHeaders(
                org.http4s.Headers.of(org.http4s.Header.Raw(ci"If-Modified-Since", firstModified.toString()))
              )

            served(request1).flatTap(r => logger.debug("headers" + r.headers.headers.mkString(","))) >>
              logger.trace("first modified " + firstModified) >>
              // You need these ... otherwise no caching.
              // https://simonhearne.com/2022/caching-header-best-practices/
              assertIOBoolean(served(request1).map(_.headers.get(ci"ETag").isDefined)) >>
              assertIOBoolean(served(request1).map(_.headers.get(ci"Cache-Control").isDefined)) >>
              assertIOBoolean(served(request1).map(_.headers.get(ci"Expires").isDefined)) >>
              assertIOBoolean(served(request1).map(_.headers.get(ci"Last-Modified").isDefined)) >>
              // Don't forget to set them _all_
              assertIO(served(request1).map(_.status.code), 200) >>
              assertIO(served(request2).map(_.status.code), 304) >>
              IO.sleep(
                1500.millis
              ) >> // have to wait at least one second otherwish last modified could be the same, if test took <1 sceond to get to this point
              IO.blocking(os.write.over(staticDir / "index.html", """<head><title>Test</title></head>""")) >>
              served(request2).flatMap(_.bodyText.compile.string).flatMap(s => logger.trace(s)) >>
              assertIO(served(request2).map(_.status.code), 200)

        }
  }

end RoutesSuite
