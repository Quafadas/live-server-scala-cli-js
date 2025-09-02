package io.github.quafadas.sjsls

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

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

inline def respondWithCacheLastModified(resp: Response[IO], lastModZdt: ZonedDateTime) =
  resp.putHeaders(
    Header.Raw(ci"Cache-Control", "no-cache"),
    Header.Raw(ci"ETag", lastModZdt.toInstant.getEpochSecond.toString()),
    Header.Raw(
      ci"Last-Modified",
      formatter.format(lastModZdt)
    ),
    Header.Raw(
      ci"Expires",
      httpCacheFormat(ZonedDateTime.ofInstant(Instant.now().plusSeconds(10000000), ZoneId.of("GMT")))
    )
  )
end respondWithCacheLastModified

inline def cachedFileResponse(epochInstant: Instant, fullPath: Path, req: Request[IO], service: HttpRoutes[IO])(
    logger: Scribe[IO]
) =
  OptionT
    .liftF(fileLastModified(fullPath))
    .flatMap {
      lastmod =>
        req.headers.get(ci"If-Modified-Since") match
          case Some(header) =>
            val browserLastModifiedAt = header.head.value
            service(req).semiflatMap {
              resp =>
                val zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastmod), ZoneId.of("GMT"))
                val response =
                  if parseFromHeader(epochInstant, browserLastModifiedAt) == lastmod then
                    logger.debug(s"Time matches, returning 304 for ${req.uri.path}") >>
                      IO(
                        respondWithCacheLastModified(Response[IO](Status.NotModified), zdt)
                      )
                  else
                    logger.debug(lastmod.toString()) >>
                      logger.debug(s"Last modified doesn't match, returning 200 for ${req.uri.path}") >>
                      IO(
                        respondWithCacheLastModified(resp, zdt)
                      )
                  end if
                end response
                logger.debug(lastmod.toString()) >>
                  logger.debug(parseFromHeader(epochInstant, browserLastModifiedAt).toString()) >>
                  response
            }
          case _ =>
            OptionT.liftF(
              logger.debug(s"No If-Modified-Since headers in request ${req.uri.path}") ) >>
              service(req).map {
                resp =>
                  respondWithCacheLastModified(
                    resp,
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastmod), ZoneId.of("GMT"))
                  )
              }

      end match
    }

object StaticMiddleware:

  def apply(service: HttpRoutes[IO], staticDir: Path)(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>
      val epochInstant: Instant = Instant.EPOCH
      val fullPath = staticDir / req.uri.path.toString.drop(1)

      cachedFileResponse(epochInstant, fullPath, req, service)(logger: Scribe[IO])
  }
end StaticMiddleware
