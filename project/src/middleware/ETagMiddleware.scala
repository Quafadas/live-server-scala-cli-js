import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.dsl.io.*
import org.typelevel.ci.CIStringSyntax

import scribe.Scribe

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*

object ETagMiddleware:

  def apply(service: HttpRoutes[IO], mr: Ref[IO, Map[String, String]])(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>

      def respondWithEtag(resp: Response[IO]) =
        mr.get
          .flatMap {
            map =>
              map.get(req.uri.path.toString.drop(1)) match
                case Some(hash) =>
                  logger.debug(req.uri.toString) >>
                    IO(resp.putHeaders(Header.Raw(ci"ETag", hash)))
                case None =>
                  logger.debug(req.uri.toString) >>
                    IO(resp)
            end match
          }
      end respondWithEtag

      req.headers.get(ci"If-None-Match") match
        case Some(header) =>
          val etag = header.head.value
          // OptionT.liftF(logger.debug(req.uri.toString)) >>
          //   OptionT.liftF(logger.debug(etag)) >>
          service(req).semiflatMap {
            resp =>
              mr.get
                .flatMap {
                  map =>
                    map.get(req.uri.path.toString.drop(1)) match
                      case Some(foundEt) =>
                        if etag == foundEt then
                          logger.debug("ETag matches, returning 304") >>
                            IO(Response[IO](Status.NotModified))
                        else
                          logger.debug(etag) >>
                            logger.debug("ETag doesn't match, returning 200") >>
                            respondWithEtag(resp)
                        end if
                      case None =>
                        respondWithEtag(resp)
                }
          }
        case _ =>
          OptionT.liftF(logger.debug("No ETag header in query, service it")) >>
            service(req).semiflatMap {
              resp =>
                respondWithEtag(resp)
            }
      end match
  }
end ETagMiddleware
