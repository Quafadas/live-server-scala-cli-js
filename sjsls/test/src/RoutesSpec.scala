package io.github.quafadas.sjsls

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import scala.concurrent.duration.*

import org.http4s.*
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.server.middleware.ErrorAction
import org.typelevel.ci.CIStringSyntax

import fs2.concurrent.Topic
import fs2.io.file.Files
import fs2.io.file.Path

import scribe.Level
import scribe.Scribe

import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.MapRef

import munit.CatsEffectSuite

class RoutesSuite extends CatsEffectSuite:

  val md = MessageDigest.getInstance("MD5")
  val testStr = "const hi = 'Hello, world'"
  val simpleCss = "h1 {color: red;}"
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
      os.write(tempDir / "index.less", simpleCss)
      os.write(tempDir / "image.webp", os.read.bytes(os.resource / "cat.webp"))
      tempDir
    ,
    teardown = tempDir =>
      // Always gets called, even if test failed.
      os.remove.all(tempDir)
  )

  val externalIndexR =
    ResourceFunFixture {
      IO {
        val tempDir = os.temp.dir()
        // create a file in the folder
        val tempFile = tempDir / "index.html"
        os.write(tempFile, """<head><title>Test</title></head><body><h1>Test</h1></body>""")
        os.write(tempDir / "index.less", simpleCss)
        os.write(tempDir / "image.webp", os.read.bytes(os.resource / "cat.webp"))
        tempDir
      }.toResource
    }

  val externalSyles = FunFixture[os.Path](
    setup = test =>
      val tempDir = os.temp.dir()
      os.write(tempDir / "index.less", "h1 {color: red;}")
      tempDir
    ,
    teardown = tempDir =>
      // Always gets called, even if test failed.
      os.remove.all(tempDir)
  )

  override def beforeAll(): Unit =
    scribe
      .Logger
      .root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(Level.get("info").get))
      .replace()

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
        _ <- IO.sleep(200.millis).toResource // wait for watcher to start
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
          scribe.cats[IO]
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
                assertIOBoolean(resp3.bodyText.compile.string.map(_.contains("src=\"/main.js"))) >>
                  IO.unit
            }

          val requestHtml = Request[IO](uri = uri"/")
          // val etag = "699892091"

          val checkRespHtml = client
            .run(requestHtml)
            .use {
              respH =>
                assertEquals(respH.status.code, 200)
                assertEquals(respH.headers.get(ci"ETag").isDefined, true)
                IO.unit
            }

          // val requestHtml2 = Request[IO](uri = uri"/").withHeaders(Header.Raw(ci"If-None-Match", etag))

          // val checkRespHtml2 = client
          //   .run(requestHtml2)
          //   .use {
          //     respH =>
          //       assertEquals(respH.status.code, 304)
          //       IO.unit
          //   }

          checkResp1 >> checkResp2 >> checkRespSpa >> checkRespHtml

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
              assertIO(responseLess.map(_.status.code), 200) >>
              assertIO(responseLess.flatMap(_.bodyText.compile.string), simpleCss)
        }
    }

  FunFixture
    .map2(files, externalSyles)
    .test("That styles and SPA play nicely together") {
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
            Some("app")
          )(logger)
        yield theseRoutes.orNotFound

        app.use {
          served =>
            val requestLess = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/index.less"))
            val responseLess = served(requestLess)
            assertIO(responseLess.map(_.status.code), 200) >>
              assertIO(responseLess.flatMap(_.bodyText.compile.string), simpleCss)
        }
    }

  externalIndexHtml.test("Static files are updated when needed and cached otherwise") {
    staticDir =>
      val app = for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        fileToHashMapRef = MapRef.fromSingleImmutableMapRef[IO, String, String](fileToHashRef)
        refreshPub <- Topic[IO, Unit].toResource
        _ <- logger.trace(os.stat(staticDir / "image.webp").toString()).toResource
        modifedAt <- fileLastModified((staticDir / "image.webp").toFs2)
          .map {
            seconds =>
              httpCacheFormat(ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("GMT")))
          }
          .toResource
        theseRoutes <- routes(
          os.temp.dir().toString,
          refreshPub,
          Some(IndexHtmlConfig.IndexHtmlPath(staticDir.toFs2)),
          HttpRoutes.empty[IO],
          fileToHashRef,
          None
        )(logger)
      yield (theseRoutes.orNotFound, logger, modifedAt)

      app.use {
        case (served, logger, firstModified) =>
          val request1 = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/image.webp"))

          val request2 = org
            .http4s
            .Request[IO](uri = org.http4s.Uri.unsafeFromString("/image.webp"))
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
            ) >>
            IO.blocking(os.write.over(staticDir / "image.webp", os.read.bytes(os.resource / "dog.webp"))) >>
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
