import java.security.MessageDigest

import org.http4s.HttpRoutes
import org.typelevel.ci.CIStringSyntax

import fs2.concurrent.Topic
import fs2.io.file.Path

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.MapRef

import munit.CatsEffectSuite

import scala.concurrent.duration.*

class RoutesSuite extends CatsEffectSuite:

  val md = MessageDigest.getInstance("MD5")
  val testStr = "const hi = 'Hello, world'"
  val testHash = md.digest(testStr.getBytes()).map("%02x".format(_)).mkString

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

  files
    .test("That the routes serve files on first call with a 200, that the eTag is set, and on second call with a 304") {
      tempDir =>
        val app = for
          logger <- IO(scribe.cats[IO]).toResource
          fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
          _ <- updateMapRef(tempDir.toFs2, fileToHashRef)(logger).toResource
          refreshPub <- Topic[IO, Unit].toResource
          theseRoutes <- routes(
            tempDir.toString,
            refreshPub,
            None,
            HttpRoutes.empty[IO],
            fileToHashRef
          )(logger)
        yield theseRoutes

        app.use {
          served =>
            val request = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString("/test.js"))
            val response = served(request)

            val checks = assertIO(response.map(_.status.code), 200) >>
              assertIO(response.map(_.headers.get(ci"ETag").isDefined), true) >>
              assertIO(
                response.map(re => re.headers.get(ci"ETag").get.head.value),
                testHash
              ) // hash of "const hi = 'Hello, world'"

            // And that if we recieve the If-None-Match header with the correct hash, we respond with a 304
            val request2 = org
              .http4s
              .Request[IO](uri = org.http4s.Uri.unsafeFromString("/test.js"))
              .withHeaders(
                org.http4s.Headers.of(org.http4s.Header.Raw(ci"If-None-Match", testHash))
              )
            val response2 = served(request2)

            val testWithETag =
              for resp <- response2
              yield assertEquals(resp.status.code, 304)

            checks >> testWithETag

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
            fileToHashRef
          )(logger)
        yield theseRoutes

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
          fileToHashRef
        )(logger)
      yield theseRoutes

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
            fileToHashRef
          )(logger)
        yield theseRoutes

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

end RoutesSuite
