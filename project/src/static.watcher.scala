import fs2.*
import fs2.concurrent.Topic
import fs2.io.Watcher
import fs2.io.Watcher.Event
import fs2.io.Watcher.Event.Created
import fs2.io.Watcher.Event.Deleted
import fs2.io.Watcher.Event.Modified
import fs2.io.Watcher.Event.NonStandard
import fs2.io.Watcher.Event.Overflow

import org.http4s.Header
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.ServerSentEvent
import org.http4s.Status
import org.http4s.scalatags.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.staticcontent.*
import org.http4s.server.staticcontent.FileService
import org.typelevel.ci.CIStringSyntax

import scribe.Scribe

import cats.effect.IO
import cats.effect.OutcomeIO
import cats.effect.ResourceIO
import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*

import scala.concurrent.duration.*
import cats.effect.std.MapRef
import fs2.io.file.Path
import cats.data.OptionT

def buildRunnerMill(
    refreshTopic: Topic[IO, Unit],
    staticDir: fs2.io.file.Path
    // mr: MapRef[IO, String, Option[String]]
)(
    logger: Scribe[IO]
): ResourceIO[IO[OutcomeIO[Unit]]] =
  val nioPath = staticDir.toNioPath
  fs2
    .Stream
    .resource(Watcher.default[IO].evalTap(_.watch(nioPath)))
    .flatMap {
      _.events(500.millis)
        .evalTap {
          (e: Event) =>
            e match
              case Created(path, count) =>
                for
                  lastModified <- fileLastModified(path)
                  _ <- logger.trace(s"$path was created at $lastModified")
                // serveAt = path.relativize(nioPath)
                // _ <- mr.setKeyValue(serveAt.toString(), lastModified)
                yield ()
                end for

              case Deleted(path, count) =>
                for
                  lastModified <- fileLastModified(path)
                  _ <- logger.trace(s"$path was deleted at $lastModified")
                // serveAt = path.relativize(nioPath)
                // _ <- mr.unsetKey(serveAt.toString())
                yield ()
              case Modified(path, count) =>
                for
                  lastModified <- fileLastModified(path)
                  _ <- logger.trace(s"$path was modified at $lastModified")
                  // serveAt = path.relativize(nioPath)
                  // _ <- mr.setKeyValue(serveAt.toString(), lastModified)
                  _ <- refreshTopic.publish1(())
                yield ()
              case Overflow(count)                         => logger.trace("overflow")
              case NonStandard(event, registeredDirectory) => logger.trace("non-standard")

        }
    }
    .compile
    .drain
    .background

end buildRunnerMill

object StaticMiddleware:

  def apply(service: HttpRoutes[IO], staticDir: Path)(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>
      val fullPath = staticDir / req.uri.path.toString.drop(1)

      val filSystemModifed = for
        lastmod <- fileLastModified(fullPath)
        _ <- logger.trace(s"asked for file ${fullPath.toString}")
      yield lastmod

      OptionT
        .liftF(filSystemModifed)
        .flatMap {
          lastmod =>
            req.headers.get(ci"If-Modified-Since") match
              case Some(header) =>
                val browserLastModifiedAt = header.head.value
                OptionT.liftF(IO.println("Compare")) >>
                  OptionT.liftF(IO.println(browserLastModifiedAt)) >>
                  OptionT.liftF(IO.println(lastmod)) >>
                  service(req).semiflatMap {
                    resp =>
                      if browserLastModifiedAt == lastmod.toString() then
                        logger.debug("Time matches, returning 304") >>
                          IO(Response[IO](Status.NotModified))
                      else
                        logger.debug(lastmod.toString()) >>
                          logger.debug("Last modified doesn't match, returning 200") >>
                          IO(resp)
                      end if
                  }
              case _ =>
                OptionT.liftF(logger.debug("No headers in query, service it")) >>
                  service(req)
          end match
        }
  }
end StaticMiddleware
