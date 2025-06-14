package io.github.quafadas.sjsls

import java.time.ZonedDateTime

import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.StaticFile
import org.http4s.dsl.io.*
import org.http4s.server.Router
import org.http4s.server.staticcontent.FileService
import org.http4s.server.staticcontent.fileService

import fs2.text

import scribe.Scribe

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*

def staticAssetRoutes(
    indexOpts: Option[IndexHtmlConfig],
    modules: Ref[IO, Map[String, String]],
    zdt: ZonedDateTime,
    injectPreloads: Boolean
)(logger: Scribe[IO]): HttpRoutes[IO] =
  indexOpts match
    case None => generatedIndexHtml(injectStyles = false, modules, zdt, injectPreloads)(logger)

    case Some(IndexHtmlConfig.IndexHtmlPath(path)) =>
      HttpRoutes
        .of[IO] {
          case req @ GET -> Root => serveIndexHtml(path, modules, injectPreloads)

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
      )(logger).combineK(generatedIndexHtml(injectStyles = true, modules, zdt, injectPreloads)(logger))

def serveIndexHtml(from: fs2.io.file.Path, modules: Ref[IO, Map[String, String]], injectPreloads: Boolean) = StaticFile
  .fromPath[IO](from / "index.html")
  .getOrElseF(NotFound())
  .flatMap {
    f =>
      f.body
        .through(text.utf8.decode)
        .compile
        .string
        .flatMap {
          body =>
            for str <- if injectPreloads then (injectModulePreloads(modules, body)) else IO.pure(body)
            yield
              val bytes = str.getBytes()
              f.withEntity(bytes)
              Response[IO]().withEntity(bytes).putHeaders("Content-Type" -> "text/html")

        }
  }
