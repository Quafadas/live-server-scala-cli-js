package io.github.quafadas.sjsls

import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.StaticFile
import cats.effect.kernel.Async
import fs2.io.file.Files
import cats.MonadThrow

import org.http4s.server.staticcontent.FileService
import org.http4s.server.staticcontent.fileService
import org.http4s.Response
import org.http4s.Status

def appRoute[F[_]: Files: MonadThrow](stringPath: String)(using f: Async[F]): HttpRoutes[F] = HttpRoutes.of[F] {

  case req @ GET -> Root / fName ~ "js" =>
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

  case req @ GET -> Root / fName ~ "map" =>
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

}

def fileRoute[F[_]: Async](stringPath: String) = fileService[F](FileService.Config(stringPath))

def spaRoute[F[_]: Async](stringPath: String) = HttpRoutes[F] {
  case GET -> _ =>
    StaticFile.fromPath(fs2.io.file.Path(stringPath))
}
