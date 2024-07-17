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

  val linkedAppWithCaching: HttpRoutes[IO] = ETagMiddleware(appRoute[IO](stringPath), ref)(logger)
  val spaRoutes = clientRoutingPrefix.map(s => (s, buildSpaRoute(indexOpts, ref, zdt)(logger)))
  val staticRoutes = Some(staticAssetRoutes(indexOpts, ref, zdt)(logger))

  val routes =
    frontendRoutes[IO](
      clientSpaRoutes = spaRoutes,
      staticAssetRoutes = staticRoutes,
      appRoutes = Some(linkedAppWithCaching)
    )

  val refreshableApp = traceLogger(
    refreshRoutes(refreshTopic).combineK(proxyRoutes).combineK(routes)
  )

  IO(refreshableApp).toResource

end routes
