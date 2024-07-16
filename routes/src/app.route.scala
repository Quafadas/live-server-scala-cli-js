package io.github.quafadas.sjsls

import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.StaticFile
import cats.effect.kernel.Async

def appRoute[F[_]: Async](stringPath: String): HttpRoutes[F] = HttpRoutes[F] {
  case req @ GET -> Root / fName ~ ".js" =>
    StaticFile.fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))

  case req @ GET -> Root / fName ~ ".map" =>
    StaticFile.fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))

}
