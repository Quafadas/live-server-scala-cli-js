package io.github.quafadas.sjsls

import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.Response
import org.http4s.StaticFile
import org.http4s.Status
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.staticcontent.FileService
import org.http4s.server.staticcontent.fileService

import org.typelevel.ci.CIStringSyntax

import fs2.io.file.Files

import cats.effect.kernel.Async
import cats.syntax.all.*

import scribe.Scribe

private val hashedPattern = ".*\\.[a-f0-9]{8,}\\..*".r

private def cacheHeaders[F[_]](key: String)(resp: Response[F]): Response[F] =
  if hashedPattern.matches(key) then
    resp.putHeaders(Header.Raw(ci"Cache-Control", "public, max-age=31536000, immutable"))
  else resp.putHeaders(Header.Raw(ci"Cache-Control", "no-cache, must-revalidate"))

def appRoute[F[_]: Files](stringPath: String)(using f: Async[F]): HttpRoutes[F] = HttpRoutes.of[F] {

  case req @ GET -> Root / fName ~ "js" =>
    val key = req.uri.path.renderString
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / key, Some(req))
      .map(cacheHeaders(key))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

  case req @ GET -> Root / fName ~ "wasm" =>
    val key = req.uri.path.renderString
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / key, Some(req))
      .map(cacheHeaders(key))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

  case req @ GET -> Root / fName ~ "map" =>
    val key = req.uri.path.renderString
    StaticFile
      .fromPath(fs2.io.file.Path(stringPath) / key, Some(req))
      .map(cacheHeaders(key))
      .getOrElseF(f.pure(Response[F](Status.NotFound)))

}

def appRouteInMemory[F[_]](lookup: String => Option[Array[Byte]])(using
    f: Async[F],
    logger: Scribe[F]
): HttpRoutes[F] =
  def contentTypeFor(ext: String): `Content-Type` =
    MediaType.forExtension(ext).fold(`Content-Type`(MediaType.application.`octet-stream`))(m => `Content-Type`(m))

  def serve(req: org.http4s.Request[F], ext: String): F[Response[F]] =
    val key = req.uri.path.renderString.stripPrefix("/")
    lookup(key) match
      case Some(bytes) =>
        val resp = cacheHeaders(key)(Response[F](Status.Ok).withEntity(bytes).withContentType(contentTypeFor(ext)))
        logger.debug(
          s"[appRouteInMemory] HIT  ext=$ext key='$key' size=${bytes.length} bytes hashed=${hashedPattern.matches(key)}"
        ) >> f.pure(resp)
      case None =>
        logger.debug(
          s"[appRouteInMemory] MISS ext=$ext key='$key'"
        ) >> f.pure(Response[F](Status.NotFound))
    end match
  end serve

  HttpRoutes.of[F] {
    case req @ GET -> Root / fName ~ "js"   => serve(req, "js")
    case req @ GET -> Root / fName ~ "wasm" => serve(req, "wasm")
    case req @ GET -> Root / fName ~ "map"  => serve(req, "map")
  }
end appRouteInMemory

def fileRoute[F[_]: Async](stringPath: String) = fileService[F](FileService.Config(stringPath))

def spaRoute[F[_]: Async](stringPath: String) = HttpRoutes[F] {
  case GET -> _ =>
    StaticFile.fromPath(fs2.io.file.Path(stringPath))
}
