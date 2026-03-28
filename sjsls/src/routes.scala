package io.github.quafadas.sjsls

import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.Response
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import fs2.*
import fs2.concurrent.Topic
import fs2.io.file.Files

import scribe.Scribe

import cats.MonadThrow
import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*

// TODO: Test that the map of hashes is updated, when an external build tool is responsible for refresh pulses

private def devToolsRoute(workspace: Option[(String, String)]): HttpRoutes[IO] =
  workspace match
    case None               => HttpRoutes.empty[IO]
    case Some((root, uuid)) =>
      val json = s"""{"workspace":{"root":"$root","uuid":"$uuid"}}"""
      HttpRoutes.of[IO] {
        case GET -> Root / ".well-known" / "appspecific" / "com.chrome.devtools.json" =>
          Ok(json, `Content-Type`(MediaType.application.json))
      }

def routes[F[_]: Files: MonadThrow](
    stringPath: String,
    refreshTopic: Topic[IO, Unit],
    assetRefreshTopic: Topic[IO, String],
    indexOpts: Option[IndexHtmlConfig],
    proxyRoutes: HttpRoutes[IO],
    ref: Ref[IO, Map[String, String]],
    clientRoutingPrefix: Option[String],
    injectPreloads: Boolean,
    buildTool: BuildTool,
    devToolsWorkspace: Option[(String, String)] = None,
    inMemoryFiles: Option[ConcurrentHashMap[String, Array[Byte]]] = None
)(logger: Scribe[IO]): Resource[IO, HttpRoutes[IO]] =

  val traceLogger = traceLoggerMiddleware(logger)
  val zdt = ZonedDateTime.now()

  val linkedAppWithCaching: HttpRoutes[IO] = inMemoryFiles match
    case Some(files) =>
      val lookup: String => Option[Array[Byte]] = name => Option(files.get(name))
      appRouteInMemory[IO](lookup)(using Async[IO], logger)
    case None =>
      ETagMiddleware(appRoute[IO](stringPath), ref)(logger)
  val spaRoutes = clientRoutingPrefix.map(s => (s, buildSpaRoute(indexOpts, ref, zdt, injectPreloads)(logger)))
  val staticRoutes = Some(staticAssetRoutes(indexOpts, ref, zdt, injectPreloads)(logger))

  val routes =
    frontendRoutes[IO](
      clientSpaRoutes = spaRoutes,
      staticAssetRoutes = staticRoutes,
      appRoutes = Some(linkedAppWithCaching)
    )

  val refreshableApp = traceLogger(
    devToolsRoute(devToolsWorkspace)
      .combineK(refreshRoutes(refreshTopic, assetRefreshTopic, buildTool, fs2.io.file.Path(stringPath), ref, logger, inMemoryFiles))
      .combineK(proxyRoutes)
      .combineK(routes)
  )
  logger.info("Routes created  at : ").toResource >>
    logger.info("Path: " + stringPath).toResource >>
    (inMemoryFiles match
      case Some(files) =>
        logger
          .debug(
            s"[routes] Using IN-MEMORY appRoute. inMemoryFiles.size=${files
                .size()} keys=${scala.jdk.CollectionConverters.SetHasAsScala(files.keySet()).asScala.mkString(", ")}"
          )
          .toResource
      case None =>
        logger.debug(s"[routes] Using DISK appRoute. path=$stringPath").toResource) >>
    IO(refreshableApp).toResource

end routes
