package io.github.quafadas.sjsls

import io.github.quafadas.sjsls.StaticFileMiddleware
import cats.effect.IO
import org.http4s.HttpRoutes
import scribe.Scribe
import org.http4s.dsl.io.*
import org.http4s.StaticFile
import fs2.text
import cats.effect.kernel.Ref
import org.http4s.Response
import cats.effect.kernel.Async
import org.http4s.scalatags.*
import java.time.ZonedDateTime

/** This is expected to be hidden behind a route with the SPA prefix. It will serve the index.html file from all routes.
  *
  * @param indexOpts
  * @param modules
  * @param zdt
  * @param logger
  * @return
  */
def buildSpaRoute(indexOpts: Option[IndexHtmlConfig], modules: Ref[IO, Map[String, String]], zdt: ZonedDateTime)(
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
          case req @ GET -> _ => serveIndexHtml(dir)
        },
        dir / "index.html"
      )(logger)
end buildSpaRoute
