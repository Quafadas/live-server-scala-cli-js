package io.github.quafadas.sjsls

import java.time.ZonedDateTime

import org.http4s.HttpRoutes

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

def routes[F[_]: Files: MonadThrow](
    stringPath: String,
    refreshTopic: Topic[IO, Unit],
    indexOpts: Option[IndexHtmlConfig],
    proxyRoutes: HttpRoutes[IO],
    ref: Ref[IO, Map[String, String]],
    clientRoutingPrefix: Option[String],
    injectPreloads: Boolean
)(logger: Scribe[IO]): Resource[IO, HttpRoutes[IO]] =

  val traceLogger = traceLoggerMiddleware(logger)
  val zdt = ZonedDateTime.now()

  val linkedAppWithCaching: HttpRoutes[IO] = ETagMiddleware(appRoute[IO](stringPath), ref)(logger)
  val spaRoutes = clientRoutingPrefix.map(s => (s, buildSpaRoute(indexOpts, ref, zdt, injectPreloads)(logger)))
  val staticRoutes = Some(staticAssetRoutes(indexOpts, ref, zdt, injectPreloads)(logger))

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
