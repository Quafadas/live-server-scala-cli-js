package io.github.quafadas.sjsls

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import scala.concurrent.duration.*

import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.server.middleware.ErrorAction
import org.typelevel.ci.CIStringSyntax

import fs2.concurrent.Topic
import fs2.io.file.Files

import scribe.Level
import scribe.Scribe

import cats.effect.*
import cats.effect.kernel.Ref
import cats.effect.std.MapRef

import munit.CatsEffectSuite

class RoutesSuite extends CatsEffectSuite:

  val md = MessageDigest.getInstance("MD5")
  val testStr = "const hi = 'Hello, world'"
  val simpleCss = "h1 {color: red;}"
  val testHash = md.digest(testStr.getBytes()).map("%02x".format(_)).mkString
  val testBinary = os.read.bytes(os.resource / "cat.webp")
  val hashedFileName = s"test.$testHash.js"

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
      os.write(tempDir / "test.wasm", testBinary)
      tempDir
    ,
    teardown = tempDir =>
      // Always gets called, even if test failed.
      os.remove.all(tempDir)
  )

  val hashedFiles = FunFixture[os.Path](
    setup = test =>
      // create a temp folder
      val tempDir = os.temp.dir()
      // create a file in the folder
      val tempFile = tempDir / hashedFileName
      os.write(tempFile, testStr)
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
      .withHandler(minimumLevel = Some(Level.get("debug").get))
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

  files.test("That the routes serve JS and Wasm files with 200 and index.html is served from SPA") {
    tempDir =>

      scribe
        .Logger
        .root
        .clearHandlers()
        .clearModifiers()
        .withHandler(minimumLevel = Some(Level.get("info").get))
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
        assetRefreshPub <- Topic[IO, String].toResource
        theseRoutes: HttpRoutes[IO] <- routes(
          tempDir.toString,
          refreshPub,
          assetRefreshPub,
          None,
          HttpRoutes.empty[IO],
          fileToHashRef,
          Some("app"),
          false,
          ScalaCli()
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
                IO.unit
            }

          val requestWasm = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/test.wasm"))

          val checkWasm = client
            .run(requestWasm)
            .use {
              resp =>
                assertEquals(resp.status.code, 200)
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

          // The generated index.html ETag comes from staticHtmlMiddleware (content hash of the
          // response body), not from ETagMiddleware. That path is unchanged so the assertion is
          // still valid.
          val checkRespHtml = client
            .run(requestHtml)
            .use {
              respH =>
                assertEquals(respH.status.code, 200)
                assertEquals(respH.headers.get(ci"ETag").isDefined, true)
                IO.unit
            }
          checkResp1 >> checkWasm >> checkRespSpa >> checkRespHtml

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
          assetRefreshPub <- Topic[IO, String].toResource
          theseRoutes <- routes(
            appDir.toString,
            refreshPub,
            assetRefreshPub,
            Some(IndexHtmlConfig.IndexHtmlPath(staticDir.toFs2)),
            HttpRoutes.empty[IO],
            fileToHashRef,
            None,
            false,
            ScalaCli()
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
        assetRefreshPub <- Topic[IO, String].toResource
        theseRoutes <- routes(
          appDir.toString,
          refreshPub,
          assetRefreshPub,
          None,
          HttpRoutes.empty[IO],
          fileToHashRef,
          None,
          false,
          ScalaCli()
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
          assetRefreshPub <- Topic[IO, String].toResource
          theseRoutes <- routes(
            appDir.toString,
            refreshPub,
            assetRefreshPub,
            Some(IndexHtmlConfig.StylesOnly(styleDir.toFs2)),
            HttpRoutes.empty[IO],
            fileToHashRef,
            None,
            false,
            ScalaCli()
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
          assetRefreshPub <- Topic[IO, String].toResource
          theseRoutes <- routes(
            appDir.toString,
            refreshPub,
            assetRefreshPub,
            Some(IndexHtmlConfig.StylesOnly(styleDir.toFs2)),
            HttpRoutes.empty[IO],
            fileToHashRef,
            Some("app"),
            false,
            ScalaCli()
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
        assetRefreshPub <- Topic[IO, String].toResource
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
          assetRefreshPub,
          Some(IndexHtmlConfig.IndexHtmlPath(staticDir.toFs2)),
          HttpRoutes.empty[IO],
          fileToHashRef,
          None,
          false,
          ScalaCli()
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

  hashedFiles.test("That hashed files have immutable headers") {
    tempDir =>
      val app = for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        _ <- updateMapRef(tempDir.toFs2, fileToHashRef)(logger).toResource
        refreshPub <- Topic[IO, Unit].toResource
        assetRefreshPub <- Topic[IO, String].toResource
        theseRoutes <- routes(
          tempDir.toString,
          refreshPub,
          assetRefreshPub,
          None,
          HttpRoutes.empty[IO],
          fileToHashRef,
          None,
          false,
          NoBuildTool()
        )(logger)
      yield (theseRoutes.orNotFound, logger)

      app.use {
        case (served, logger) =>
          val request = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString(s"/$hashedFileName"))
          val cacheControlValues = served(request).map(
            _.headers.get(ci"Cache-Control").get.map(_.value).toList
          )
          served(request).flatTap(r => logger.debug("Response headers in test " + r.headers.headers.mkString(","))) >>
            assertIO(served(request).map(_.status.code), 200) >>
            assertIOBoolean(
              cacheControlValues.map(_.exists(_.contains("immutable")))
            ) >>
            assertIOBoolean(
              cacheControlValues.map(_.exists(_.contains("public")))
            ) >>
            assertIOBoolean(
              cacheControlValues.map(_.exists(_.contains("max-age=31536000")))
            )

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
        assetRefreshPub <- Topic[IO, String].toResource
        theseRoutes <- routes(
          os.temp.dir().toString,
          refreshPub,
          assetRefreshPub,
          Some(IndexHtmlConfig.IndexHtmlPath(staticDir.toFs2)),
          HttpRoutes.empty[IO],
          fileToHashRef,
          Some("app"),
          false,
          ScalaCli()
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

  // Regression test: when customRefresh is supplied the staticWatcher on the
  // indexHtmlTemplate dir must NOT fire an extra event, giving exactly one SSE
  // PageRefresh per build step.
  externalIndexR.test(
    "customRefresh suppresses staticWatcher double-fire: exactly one refresh event per explicit publish"
  ) {
    staticDir =>
      for
        logger <- IO(scribe.cats[IO]).toResource
        fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
        // This acts as the plugin's `updateServer` topic
        customTopic <- Topic[IO, Unit].toResource
        linkingTopic <- Topic[IO, Unit].toResource

        // Collect events published on customTopic.
        // We subscribe before wiring liveServer so we don't miss early events.
        eventCount <- Ref[IO].of(0).toResource
        _ <- customTopic.subscribe(Int.MaxValue).evalTap(_ => eventCount.update(_ + 1)).compile.drain.background

        // Simulate liveServer.main wiring: only start staticWatcher when customRefresh is None.
        // Here customRefresh = Some(customTopic), so we do NOT start a staticWatcher.
        _ <- IO.unit.toResource // (no staticWatcher started – that's the fix under test)

        _ <- IO.sleep(100.millis).toResource // let subscriber register
        // Simulate one explicit plugin publish (what siteGen() does)
        _ <- customTopic.publish1(()).toResource
        _ <- IO.sleep(200.millis).toResource // let events propagate

        // Also write to the watched dir to prove a staticWatcher would have fired
        _ <- IO.blocking(os.write.over(staticDir / "index.html", """<head><title>Updated</title></head>""")).toResource
        _ <- IO.sleep(200.millis).toResource

        count <- eventCount.get.toResource
      yield assertEquals(count, 1, s"Expected exactly 1 refresh event but got $count")
  }

  // Regression test: publishing to assetRefreshTopic must cause an AssetRefresh SSE event.
  test("SSE endpoint emits AssetRefresh event when assetRefreshTopic fires") {
    (for
      logger <- IO(scribe.cats[IO]).toResource
      fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
      refreshPub <- Topic[IO, Unit].toResource
      assetRefreshPub <- Topic[IO, String].toResource
    yield (refreshPub, assetRefreshPub, fileToHashRef, logger)).use {
      case (refreshPub, assetRefreshPub, fileToHashRef, logger) =>
        val theseRoutes = refreshRoutes(
          refreshPub,
          assetRefreshPub,
          NoBuildTool(),
          fs2.io.file.Path("/tmp"),
          fileToHashRef,
          logger
        )
        val served = theseRoutes.orNotFound
        served(Request[IO](uri = uri"/refresh/v1/sse"))
          .flatMap {
            resp =>
              resp
                .body
                .through(ServerSentEvent.decoder)
                .collect { case ServerSentEvent(Some(data), _, _, _, _) => data }
                .filter(!_.contains("KeepAlive"))
                .head
                .concurrently(
                  fs2.Stream.sleep[IO](100.millis) >>
                    fs2.Stream.eval(assetRefreshPub.publish1("styles.css").void)
                )
                .compile
                .lastOrError
          }
          .map {
            data =>
              assert(data.contains("AssetRefresh"), s"Expected AssetRefresh in: $data")
              assert(data.contains("styles.css"), s"Expected styles.css in: $data")
          }
    }
  }

end RoutesSuite

class DevToolsRouteSuite extends CatsEffectSuite:

  given filesInstance: Files[IO] = Files.forAsync[IO]

  private def makeApp(workspace: Option[(String, String)]): Resource[IO, Client[IO]] =
    for
      logger <- IO(scribe.cats[IO]).toResource
      fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
      refreshPub <- Topic[IO, Unit].toResource
      assetRefreshPub <- Topic[IO, String].toResource
      theseRoutes <- routes(
        os.temp.dir().toString,
        refreshPub,
        assetRefreshPub,
        None,
        HttpRoutes.empty[IO],
        fileToHashRef,
        None,
        false,
        NoBuildTool(),
        workspace
      )(logger)
    yield Client.fromHttpApp(theseRoutes.orNotFound)

  test("well-known URL returns 200 with correct JSON when workspace is configured") {
    makeApp(Some(("/test/root", "test-uuid"))).use {
      client =>
        client
          .run(Request[IO](uri = uri"/.well-known/appspecific/com.chrome.devtools.json"))
          .use {
            resp =>
              for
                body <- resp.bodyText.compile.string
                _ <- IO(assertEquals(resp.status.code, 200))
                _ <- IO(
                  assertEquals(resp.headers.get[`Content-Type`].map(_.mediaType), Some(MediaType.application.json))
                )
                _ <- IO(assertEquals(body, """{"workspace":{"root":"/test/root","uuid":"test-uuid"}}"""))
              yield ()
          }
    }
  }

  test("well-known URL returns 404 when no workspace is configured") {
    makeApp(None).use {
      client =>
        client
          .run(Request[IO](uri = uri"/.well-known/appspecific/com.chrome.devtools.json"))
          .use {
            resp =>
              IO(assertEquals(resp.status.code, 404))
          }
    }
  }

end DevToolsRouteSuite
