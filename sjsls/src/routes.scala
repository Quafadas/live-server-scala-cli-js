package io.github.quafadas.sjsls

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import scala.concurrent.duration.DurationInt

import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.ServerSentEvent
import org.http4s.StaticFile
import org.http4s.Status
import org.http4s.Uri.Path.SegmentEncoder
import org.http4s.dsl.io.*
import org.http4s.scalatags.*
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
import org.http4s.server.staticcontent.*
import org.http4s.server.staticcontent.FileService
import org.typelevel.ci.CIStringSyntax
import org.http4s.EntityBody

import fs2.*
import fs2.concurrent.Topic
import fs2.io.file.Files

import scribe.Scribe

import cats.MonadThrow
import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*

import org.http4s.Http

def routes[F[_]: Files: MonadThrow](
    stringPath: String,
    refreshTopic: Topic[IO, Unit],
    indexOpts: Option[IndexHtmlConfig],
    proxyRoutes: HttpRoutes[IO],
    ref: Ref[IO, Map[String, String]],
    clientRoutingPrefix: Option[String]
)(logger: Scribe[IO]): Resource[IO, HttpRoutes[IO]] =

  val traceLogger = traceLoggerMiddleware(logger)
  val zdt = ZonedDateTime.now()

  val linkedAppWithCaching: HttpRoutes[IO] =
    ETagMiddleware(
      HttpRoutes.of[IO] {
        case req @ GET -> Root / fName ~ "js" =>
          StaticFile
            .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
            .getOrElseF(NotFound())

        case req @ GET -> Root / fName ~ "map" =>
          StaticFile
            .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
            .getOrElseF(NotFound())

      },
      ref
    )(logger)

  val linkedAppWithCaching2: HttpRoutes[IO] = ETagMiddleware(appRoute[IO](stringPath), ref)(logger)

  def clientSpaRoutes(modules: Ref[IO, Map[String, String]]): HttpRoutes[IO] =
    clientRoutingPrefix match
      case None => HttpRoutes.empty[IO]
      case Some(spaRoute) =>
        Router(spaRoute -> buildSpaRoute(indexOpts, modules, zdt)(logger))

  val app = traceLogger(
    refreshRoutes(refreshTopic)
      .combineK(proxyRoutes)
      .combineK(linkedAppWithCaching)
      .combineK(clientSpaRoutes(ref))
      .combineK(staticAssetRoutes(indexOpts, ref, zdt)(logger))
  )
  val routes = traceLogger(
    buildRoutes[IO](
      clientSpaRoutes = clientRoutingPrefix.map(
        s => (s, buildSpaRoute(indexOpts, ref, zdt)(logger))
      ), // clientRoutingPrefix.map(spa => (spa, clientSpaRoutes(ref))),
      staticAssetRoutes = Some(staticAssetRoutes(indexOpts, ref, zdt)(logger)),
      appRoutes = Some(linkedAppWithCaching)
    )
  )

  val app2 = traceLogger(
    refreshRoutes(refreshTopic)
      .combineK(proxyRoutes)
      .combineK(
        routes
      )
  )

  IO(app2).toResource

end routes
