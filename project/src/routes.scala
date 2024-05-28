import scala.concurrent.duration.DurationInt

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

import fs2.*
import fs2.concurrent.Topic
import fs2.io.file.Files

import scribe.Scribe

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*

import _root_.io.circe.syntax.EncoderOps
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
          OptionT.liftF(logger.debug("No headers in query, service it")) >>
            service(req).semiflatMap {
              resp =>
                respondWithEtag(resp)
            }
      end match
  }
end ETagMiddleware

val vanillaIndexResponse = IO(Response[IO]().withEntity(vanillaTemplate(injectStyles)).withHeaders(Header("Cache-Control", "no-cache")))

def routes(
    stringPath: String,
    refreshTopic: Topic[IO, Unit],
    indexOpts: Option[IndexHtmlConfig],
    proxyRoutes: HttpRoutes[IO],
    ref: Ref[IO, Map[String, String]]
)(logger: Scribe[IO]): Resource[IO, HttpApp[IO]] =

  val linkedAppWithCaching: HttpRoutes[IO] = ETagMiddleware(
    Router(
      "" -> fileService[IO](FileService.Config(stringPath))
    ),
    ref
  )(logger)

  def generatedIndexHtml(injectStyles: Boolean) = HttpRoutes.of[IO] {
    case GET -> Root => vanillaIndexResponse    
    case GET -> Root / "index.html" => vanillaIndexResponse      
  }

  val staticAssetRoutes: HttpRoutes[IO] = indexOpts match
    case None => generatedIndexHtml(injectStyles = false)
    case Some(IndexHtmlConfig(Some(externalPath), None)) =>
      StaticMiddleware(
        Router(
          "" -> fileService[IO](FileService.Config(externalPath.toString()))
        ),
        fs2.io.file.Path(externalPath.toString())
      )(logger)

    case Some(IndexHtmlConfig(None, Some(stylesPath))) =>
      generatedIndexHtml(injectStyles = true).combineK(
        Router(
          "" -> fileService[IO](FileService.Config(stylesPath.toString()))
        )
      )
    case _ =>
      throw new Exception(
        "A seperate style path and index.html location were defined, this is not permissable"
      ) // This should have been validated out earlier

  val refreshRoutes = HttpRoutes.of[IO] {
    // case GET -> Root / "all" =>
    //   ref
    //     .get
    //     .flatTap(m => logger.trace(m.toString))
    //     .flatMap {
    //       m =>
    //         Ok(m.toString)
    //     }
    case GET -> Root / "api" / "v1" / "sse" =>
      val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
      Ok(
        keepAlive
          .merge(refreshTopic.subscribe(10).as(PageRefresh()))
          .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
      )
  }
  val app = refreshRoutes.combineK(linkedAppWithCaching).combineK(staticAssetRoutes).combineK(proxyRoutes).orNotFound
  IO(app).toResource

end routes

def updateMapRef(stringPath: fs2.io.file.Path, mr: Ref[IO, Map[String, String]])(logger: Scribe[IO]) =
  Files[IO]
    .walk(stringPath)
    .evalFilter(Files[IO].isRegularFile)
    .parEvalMap(maxConcurrent = 8)(path => fileHash(path).map(path -> _))
    .compile
    .toVector
    .flatMap(
      vector =>
        val newMap = vector
          .view
          .map(
            (path, hash) =>
              val relativizedPath = stringPath.relativize(path).toString
              relativizedPath -> hash
          )
          .toMap
        logger.trace(s"Updated hashes $newMap") *> mr.set(newMap)
    )

private def fileWatcher(
    stringPath: fs2.io.file.Path,
    mr: Ref[IO, Map[String, String]],
    linkingTopic: Topic[IO, Unit],
    refreshTopic: Topic[IO, Unit]
)(logger: Scribe[IO]): ResourceIO[Unit] =
  linkingTopic
    .subscribe(10)
    .evalTap {
      _ =>
        updateMapRef(stringPath, mr)(logger) >> refreshTopic.publish1(())
    }
    .compile
    .drain
    .background
    .void
end fileWatcher
