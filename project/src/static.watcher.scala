import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.duration.*

import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.dsl.io.*
import org.typelevel.ci.CIStringSyntax

import fs2.*
import fs2.concurrent.Topic
import fs2.io.Watcher
import fs2.io.Watcher.Event
import fs2.io.Watcher.Event.Created
import fs2.io.Watcher.Event.Deleted
import fs2.io.Watcher.Event.Modified
import fs2.io.Watcher.Event.NonStandard
import fs2.io.Watcher.Event.Overflow
import fs2.io.file.Path

import scribe.Scribe

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.OutcomeIO
import cats.effect.ResourceIO
import cats.syntax.all.*

def staticWatcher(
    refreshTopic: Topic[IO, Unit],
    staticDir: fs2.io.file.Path
    // mr: MapRef[IO, String, Option[String]]
)(
    logger: Scribe[IO]
): ResourceIO[IO[OutcomeIO[Unit]]] =
  val nioPath = staticDir.toNioPath

  def refreshAsset(path: java.nio.file.Path, op: String): IO[Unit] =
    for
      // lastModified <- fileLastModified(path)
      _ <- logger.trace(s"$path was $op ")
      // serveAt = path.relativize(nioPath)
      // _ <- mr.setKeyValue(serveAt.toString(), lastModified)
      _ <- fs2
        .io
        .file
        .Files[IO]
        .isRegularFile(Path(path.toString()))
        .map(b => b && !path.toString().endsWith(".less")) // don't force a refrseh if we're editing a .less file
        .ifM(
          refreshTopic.publish1(()),
          IO.unit
        )
    yield ()

  fs2
    .Stream
    .resource(Watcher.default[IO].evalTap(_.watch(nioPath)))
    .flatMap {
      _.events(500.millis)
        .evalTap {
          (e: Event) =>
            e match
              case Created(path, count) =>
                refreshAsset(path, "modified")
              case Deleted(path, count) =>
                logger.trace(s"$path was deleted, not requesting refresh")
              case Modified(path, count) =>
                refreshAsset(path, "modified")
              case Overflow(count)                         => logger.trace("overflow")
              case NonStandard(event, registeredDirectory) => logger.trace("non-standard")

        }
    }
    .compile
    .drain
    .background

end staticWatcher

val formatter = DateTimeFormatter.RFC_1123_DATE_TIME

def httpCacheFormat(zdt: ZonedDateTime): String =
  formatter.format(zdt)

object StaticMiddleware:
  def apply(service: HttpRoutes[IO], staticDir: Path)(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>

      val epochInstant: Instant = Instant.EPOCH
      val fullPath = staticDir / req.uri.path.toString.drop(1)

      def respondWithCacheLastModified(resp: Response[IO], lastModZdt: ZonedDateTime) =
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

      def parseFromHeader(header: String): Long =
        java.time.Duration.between(epochInstant, ZonedDateTime.parse(header, formatter)).toSeconds()
      end parseFromHeader

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
                      if parseFromHeader(browserLastModifiedAt) == lastmod then
                        logger.debug("Time matches, returning 304") >>
                          IO(
                            respondWithCacheLastModified(Response[IO](Status.NotModified), zdt)
                          )
                      else
                        logger.debug(lastmod.toString()) >>
                          logger.debug("Last modified doesn't match, returning 200") >>
                          IO(
                            respondWithCacheLastModified(resp, zdt)
                          )
                      end if
                    end response
                    logger.debug(lastmod.toString()) >>
                      logger.debug(parseFromHeader(browserLastModifiedAt).toString()) >>
                      response
                }
              case _ =>
                OptionT.liftF(logger.debug("No headers in query, service it")) >>
                  service(req).map {
                    resp =>
                      respondWithCacheLastModified(
                        resp,
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastmod), ZoneId.of("GMT"))
                      )
                  }

          end match
        }
  }
end StaticMiddleware
