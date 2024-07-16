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
          case req @ GET -> root /: path =>
            vanillaTemplate(false, modules).map: html =>
              Response[IO]().withEntity(html)

        },
        false,
        zdt
      )(logger)

    case Some(IndexHtmlConfig.StylesOnly(dir)) =>
      StaticHtmlMiddleware(
        HttpRoutes.of[IO] {
          case GET -> root /: spaRoute /: path =>
            vanillaTemplate(true, modules).map: html =>
              Response[IO]().withEntity(html)
        },
        true,
        zdt
      )(logger)

    case Some(IndexHtmlConfig.IndexHtmlPath(dir)) =>
      StaticFileMiddleware(
        HttpRoutes.of[IO] {
          case req @ GET -> spaRoute /: path =>
            StaticFile
              .fromPath(dir / "index.html", Some(req))
              .getOrElseF(NotFound())
              .flatMap {
                f =>
                  f.body
                    .through(text.utf8.decode)
                    .compile
                    .string
                    .flatMap: body =>
                      for str <- injectModulePreloads(modules, body)
                      yield
                        val bytes = str.getBytes()
                        f.withEntity(bytes)
                        f

              }

        },
        dir / "index.html"
      )(logger)
end buildSpaRoute
