package io.github.quafadas.sjsls
import java.time.ZonedDateTime

import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.dsl.io.*
import org.http4s.scalatags.*

import scribe.Scribe

import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Ref

/** This is expected to be hidden behind a route with the SPA prefix. It will serve the index.html file from all routes.
  *
  * @param indexOpts
  * @param modules
  * @param zdt
  * @param logger
  * @return
  */
def buildSpaRoute(
    indexOpts: Option[IndexHtmlConfig],
    modules: Ref[IO, Map[String, String]],
    zdt: ZonedDateTime,
    injectPreloads: Boolean
)(
    logger: Scribe[IO]
)(using
    Async[IO]
) =
  indexOpts match
    case None =>
      // Root / spaRoute
      StaticHtmlMiddleware(
        HttpRoutes.of[IO] {
          case req @ GET -> _ =>
            vanillaTemplate(false, modules).map: html =>
              Response[IO]().withEntity(html)

        },
        false,
        zdt
      )(logger)

    case Some(IndexHtmlConfig.StylesOnly(dir)) =>
      StaticHtmlMiddleware(
        HttpRoutes.of[IO] {
          case GET -> _ =>
            vanillaTemplate(true, modules).map: html =>
              Response[IO]().withEntity(html)
        },
        true,
        zdt
      )(logger)

    case Some(IndexHtmlConfig.IndexHtmlPath(dir)) =>
      StaticFileMiddleware(
        HttpRoutes.of[IO] {
          case req @ GET -> _ => serveIndexHtml(dir, modules, injectPreloads)
        },
        dir / "index.html"
      )(logger)
end buildSpaRoute
