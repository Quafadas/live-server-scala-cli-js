import scala.concurrent.duration.DurationInt

import org.http4s.Header
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.ServerSentEvent
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.scalatags.*
import org.http4s.server.Router
import org.http4s.server.staticcontent.*
import org.http4s.server.staticcontent.FileService

import fs2.*
import fs2.concurrent.Topic
import fs2.io.file.Files

import scribe.Scribe

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*

import _root_.io.circe.syntax.EncoderOps

def routes(
    stringPath: String,
    refreshTopic: Topic[IO, Unit],
    indexOpts: Option[IndexHtmlConfig],
    proxyRoutes: HttpRoutes[IO],
    ref: Ref[IO, Map[String, String]]
)(logger: Scribe[IO]): Resource[IO, HttpApp[IO]] =

  val linkedAppWithCaching: HttpRoutes[IO] = ETagMiddleware(
    Router(
      "" -> fileService[IO](FileService.Config(stringPath))
    ),
    ref
  )(logger)

  def generatedIndexHtml(injectStyles: Boolean) = HttpRoutes.of[IO] {
    case GET -> Root =>
      IO(Response[IO]().withEntity(vanillaTemplate(injectStyles)).withHeaders(Header("Cache-Control", "no-cache")))

    case GET -> Root / "index.html" =>
      IO(Response[IO]().withEntity(vanillaTemplate(injectStyles)).withHeaders(Header("Cache-Control", "no-cache")))
  }
  // val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
  val staticAssetRoutes: HttpRoutes[IO] = indexOpts match
    case None => generatedIndexHtml(injectStyles = false)

    case Some(IndexHtmlConfig.IndexHtmlPath(path)) =>
      StaticMiddleware(
        Router(
          "" -> fileService[IO](FileService.Config(path.toString()))
        ),
        fs2.io.file.Path(path.toString())
      )(logger)

    case Some(IndexHtmlConfig.StylesOnly(stylesPath)) =>
      generatedIndexHtml(injectStyles = true).combineK(
        NoCacheMiddlware(
          Router(
            "" -> fileService[IO](FileService.Config(stylesPath.toString()))
          )
        )
      )

  val refreshRoutes = HttpRoutes.of[IO] {
    // case GET -> Root / "all" =>
    //   ref
    //     .get
    //     .flatTap(m => logger.trace(m.toString))
    //     .flatMap {
    //       m =>
    //         Ok(m.toString)
    //     }
    case GET -> Root / "api" / "v1" / "sse" =>
      val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
      Ok(
        keepAlive
          .merge(refreshTopic.subscribe(10).as(PageRefresh()))
          .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
      )
  }
  val app = refreshRoutes.combineK(linkedAppWithCaching).combineK(staticAssetRoutes).combineK(proxyRoutes).orNotFound
  IO(app).toResource

end routes

def updateMapRef(stringPath: fs2.io.file.Path, mr: Ref[IO, Map[String, String]])(logger: Scribe[IO]) =
  Files[IO]
    .walk(stringPath)
    .evalFilter(Files[IO].isRegularFile)
    .parEvalMap(maxConcurrent = 8)(path => fileHash(path).map(path -> _))
    .compile
    .toVector
    .flatMap(
      vector =>
        val newMap = vector
          .view
          .map(
            (path, hash) =>
              val relativizedPath = stringPath.relativize(path).toString
              relativizedPath -> hash
          )
          .toMap
        logger.trace(s"Updated hashes $newMap") *> mr.set(newMap)
    )

private def fileWatcher(
    stringPath: fs2.io.file.Path,
    mr: Ref[IO, Map[String, String]],
    linkingTopic: Topic[IO, Unit],
    refreshTopic: Topic[IO, Unit]
)(logger: Scribe[IO]): ResourceIO[Unit] =
  linkingTopic
    .subscribe(10)
    .evalTap {
      _ =>
        updateMapRef(stringPath, mr)(logger) >> refreshTopic.publish1(())
    }
    .compile
    .drain
    .background
    .void
end fileWatcher
