package io.github.quafadas.sjsls

import org.http4s.HttpRoutes
import org.http4s.Request
import cats.effect.IO
import java.time.ZonedDateTime
import scribe.Scribe
import cats.data.Kleisli
import org.http4s.Response
import org.http4s.Header
import org.typelevel.ci.CIStringSyntax
import java.time.Instant
import java.time.ZoneId
import cats.effect.kernel.Ref
import org.http4s.dsl.io.*
import org.http4s.StaticFile
import fs2.text
import cats.syntax.all.*
import org.http4s.server.Router
import org.http4s.server.staticcontent.FileService
import org.http4s.server.staticcontent.fileService

def staticAssetRoutes(
    indexOpts: Option[IndexHtmlConfig],
    modules: Ref[IO, Map[String, String]],
    zdt: ZonedDateTime
)(logger: Scribe[IO]): HttpRoutes[IO] =
  indexOpts match
    case None => generatedIndexHtml(injectStyles = false, modules, zdt)(logger)

    case Some(IndexHtmlConfig.IndexHtmlPath(path)) =>
      HttpRoutes
        .of[IO] {
          case req @ GET -> Root =>
            StaticFile
              .fromPath[IO](path / "index.html")
              .getOrElseF(NotFound())
              .flatMap {
                f =>
                  f.body
                    .through(text.utf8.decode)
                    .compile
                    .string
                    .flatMap {
                      body =>
                        for str <- injectModulePreloads(modules, body)
                        yield
                          val bytes = str.getBytes()
                          f.withEntity(bytes)
                          Response[IO]().withEntity(bytes).putHeaders("Content-Type" -> "text/html")

                    }
              }

        }
        .combineK(
          StaticMiddleware(
            Router(
              "" -> fileService[IO](FileService.Config(path.toString()))
            ),
            fs2.io.file.Path(path.toString())
          )(logger)
        )

    case Some(IndexHtmlConfig.StylesOnly(stylesPath)) =>
      NoCacheMiddlware(
        Router(
          "" -> fileService[IO](FileService.Config(stylesPath.toString()))
        )
      )(logger).combineK(generatedIndexHtml(injectStyles = true, modules, zdt)(logger))
