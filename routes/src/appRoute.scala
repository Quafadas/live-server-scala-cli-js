package io.github.quafadas.sjsls

import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.Response
import org.http4s.StaticFile
import org.http4s.Status
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.staticcontent.FileService
import org.http4s.server.staticcontent.fileService

import fs2.io.file.Files

import cats.effect.kernel.Async

def appRoute[F[_]: Files](stringPath: String)(using f: Async[F]): HttpRoutes[F] = HttpRoutes.of[F] {

  case req @ GET -> Root / fName ~ "js" =>
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

  case req @ GET -> Root / fName ~ "wasm" =>
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

  case req @ GET -> Root / fName ~ "map" =>
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

}

def appRouteInMemory[F[_]](lookup: String => Option[Array[Byte]])(using f: Async[F]): HttpRoutes[F] =
  def contentTypeFor(ext: String): `Content-Type` =
    MediaType.forExtension(ext).fold(`Content-Type`(MediaType.application.`octet-stream`))(m => `Content-Type`(m))

  HttpRoutes.of[F] {
    case req @ GET -> Root / fName ~ "js" =>
      lookup(req.uri.path.renderString.stripPrefix("/")) match
        case Some(bytes) => f.pure(Response[F](Status.Ok).withEntity(bytes).withContentType(contentTypeFor("js")))
        case None        => f.pure(Response[F](Status.NotFound))

    case req @ GET -> Root / fName ~ "wasm" =>
      lookup(req.uri.path.renderString.stripPrefix("/")) match
        case Some(bytes) => f.pure(Response[F](Status.Ok).withEntity(bytes).withContentType(contentTypeFor("wasm")))
        case None        => f.pure(Response[F](Status.NotFound))

    case req @ GET -> Root / fName ~ "map" =>
      lookup(req.uri.path.renderString.stripPrefix("/")) match
        case Some(bytes) => f.pure(Response[F](Status.Ok).withEntity(bytes).withContentType(contentTypeFor("map")))
        case None        => f.pure(Response[F](Status.NotFound))
  }
end appRouteInMemory

def fileRoute[F[_]: Async](stringPath: String) = fileService[F](FileService.Config(stringPath))

def spaRoute[F[_]: Async](stringPath: String) = HttpRoutes[F] {
  case GET -> _ =>
    StaticFile.fromPath(fs2.io.file.Path(stringPath))
}
