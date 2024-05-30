import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.dsl.io.*
import org.typelevel.ci.CIStringSyntax

import fs2.*
import fs2.io.file.Path

import scribe.Scribe

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.syntax.all.*
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId

def parseFromHeader(epochInstant: Instant, header: String): Long =
  java.time.Duration.between(epochInstant, ZonedDateTime.parse(header, formatter)).toSeconds()
end parseFromHeader

object StaticFileMiddleware:
  def apply(service: HttpRoutes[IO], file: Path)(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>

      val epochInstant: Instant = Instant.EPOCH

      cachedFileResponse(epochInstant, file, req, service)(logger: Scribe[IO])
  }
end StaticFileMiddleware
