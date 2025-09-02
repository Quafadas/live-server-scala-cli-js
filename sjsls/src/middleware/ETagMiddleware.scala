package io.github.quafadas.sjsls

import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.typelevel.ci.CIStringSyntax

import scribe.Scribe

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.Ref
import cats.syntax.all.*

object ETagMiddleware:

  private def respondWithEtag(mr: Ref[IO, Map[String, String]], req: Request[IO], resp: Response[IO])(
      logger: Scribe[IO]
  ) =
    mr.get
      .flatMap {

        map =>
          map.get(req.uri.path.toString.drop(1)) match
            case Some(hash) =>
              logger.debug("Map") >>
                logger.debug(map.toString) >>
                logger.debug(s"Found ETag: $hash in map for ${req.uri.path}") >>
                IO(
                  resp.putHeaders(
                    Header.Raw(ci"ETag", hash),
                    Header.Raw(ci"Cache-control", "Must-Revalidate"),
                    Header.Raw(ci"Cache-control", "No-cache"),
                    Header.Raw(ci"Cache-control", "max-age=0"),
                    Header.Raw(ci"Cache-control", "public")
                  )
                )
            case None =>
              logger.debug("No hash found in map at path :" + req.uri.toString) >>
                IO(
                  resp.putHeaders(
                    Header.Raw(ci"Cache-control", "Must-Revalidate"),
                    Header.Raw(ci"Cache-control", "No-cache"),
                    Header.Raw(ci"Cache-control", "max-age=0"),
                    Header.Raw(ci"Cache-control", "public")
                  )
                )
        end match
      }
  end respondWithEtag

  def apply(service: HttpRoutes[IO], mr: Ref[IO, Map[String, String]])(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>

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
                          logger.debug(s"ETag $etag found in cache at path ${req.uri.path}, returning 304") >>
                            logger.debug("map is: " + map.toString) >>
                            IO(Response[IO](Status.NotModified))
                        else
                          logger.debug(s"$etag not found in cache at path ${req.uri.path} returning 200") >>
                            respondWithEtag(mr, req, resp)(logger)
                        end if
                      case None =>
                        logger.debug(s"No path found in cache at path ${req.uri.path}") >>
                          respondWithEtag(mr, req, resp)(logger)
                }
          }
        case _ =>
          OptionT.liftF(logger.debug("No If-None-Match ETag header in request")) >>
            OptionT.liftF(logger.debug(s"Headers are : ${req.headers.headers.mkString(", ")} at ${req.uri.path}")) >>
            service(req).semiflatMap {
              resp =>
                logger.debug(resp.toString) >>
                  respondWithEtag(mr, req, resp)(logger)
            }
      end match
  }
end ETagMiddleware
