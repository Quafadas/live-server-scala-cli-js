import scala.concurrent.duration.DurationInt

import org.http4s.Header
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.ServerSentEvent
import org.http4s.Status
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.staticcontent.*
import org.http4s.server.staticcontent.FileService
import org.typelevel.ci.CIStringSyntax

import fs2.*
import fs2.concurrent.Topic
import fs2.io.Watcher
import fs2.io.Watcher.Event
import fs2.io.file.Files

import scribe.Scribe

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.*
import cats.effect.std.MapRef
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

def routes(
    stringPath: String,
    refreshTopic: Topic[IO, String],
    stylesPath: Option[String],
    proxyRoutes: HttpRoutes[IO],
    indexHtmlTemplate: String,
    ref: Ref[IO, Map[String, String]]
)(logger: Scribe[IO]): Resource[IO, HttpApp[IO]] =

  val staticFiles = Router(
    "" -> fileService[IO](FileService.Config(stringPath))
  )

  val styles =
    stylesPath.fold(HttpRoutes.empty[IO])(
      path =>
        Router(
          "" -> fileService[IO](FileService.Config(path))
        )
    )

  val makeIndex = ref.get.flatMap(mp => logger.trace(mp.toString())) >>
    (ref
      .get
      .map(_.toSeq.map((path, hash) => (fs2.io.file.Path(path), hash)))
      .map(mods => injectModulePreloads(mods, indexHtmlTemplate)))
      .map(html => Response[IO]().withEntity(indexHtmlTemplate).withHeaders(Header("Cache-Control", "no-cache")))

  val overrides = HttpRoutes.of[IO] {
    case GET -> Root =>
      logger.trace("GET /") >>
        makeIndex

    case GET -> Root / "index.html" =>
      logger.trace("GET /index.html") >>
        makeIndex

    case GET -> Root / "all" =>
      ref
        .get
        .flatTap(m => logger.trace(m.toString))
        .flatMap {
          m =>
            Ok(m.toString)
        }
    case GET -> Root / "api" / "v1" / "sse" =>
      val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
      Ok(
        keepAlive
          .merge(refreshTopic.subscribe(10).as(PageRefresh()))
          .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
      )
  }
  val app = overrides
    .combineK(ETagMiddleware(staticFiles, ref)(logger))
    .combineK(styles)
    .combineK(proxyRoutes)
    .orNotFound
  IO(app).toResource

end routes

def seedMapOnStart(stringPath: String, mr: MapRef[IO, String, Option[String]])(logger: Scribe[IO]) =
  val asFs2 = fs2.io.file.Path(stringPath)
  fs2
    .io
    .file
    .Files[IO]
    .walk(asFs2)
    .evalMap {
      f =>
        Files[IO]
          .isRegularFile(f)
          .ifM(
            // logger.trace(s"hashing $f") >>
            fielHash(f).flatMap(
              h =>
                val key = asFs2.relativize(f)
                logger.trace(s"hashing $f to put at $key with hash : $h") >>
                  mr.setKeyValue(key.toString(), h)
            ),
            IO.unit
          )
    }
    .compile
    .drain
    .toResource

end seedMapOnStart

private def fileWatcher(
    stringPath: fs2.io.file.Path,
    mr: MapRef[IO, String, Option[String]]
)(logger: Scribe[IO]): ResourceIO[IO[OutcomeIO[Unit]]] =
  fs2
    .Stream
    .resource(Watcher.default[IO].evalTap(_.watch(stringPath.toNioPath)))
    .flatMap {
      w =>
        w.events()
          .evalTap(
            (e: Event) =>
              e match
                case Event.Created(path, i) =>
                  // if path.endsWith(".js") then
                  logger.trace(s"created $path, calculating hash") >>
                    fielHash(fs2.io.file.Path(path.toString())).flatMap(
                      h =>
                        val serveAt = stringPath.relativize(fs2.io.file.Path(path.toString()))
                        logger.trace(s"$serveAt :: hash -> $h") >>
                          mr.setKeyValue(serveAt.toString(), h)
                    )
                // else IO.unit
                case Event.Modified(path, i) =>
                  // if path.endsWith(".js") then
                  logger.trace(s"modified $path, calculating hash") >>
                    fielHash(fs2.io.file.Path(path.toString())).flatMap(
                      h =>
                        val serveAt = stringPath.relativize(fs2.io.file.Path(path.toString()))
                        logger.trace(s"$serveAt :: hash -> $h") >>
                          mr.setKeyValue(serveAt.toString(), h)
                    )
                // else IO.unit
                case Event.Deleted(path, i) =>
                  val serveAt = stringPath.relativize(fs2.io.file.Path(path.toString()))
                  logger.trace(s"deleted $path, removing key $serveAt") >>
                    mr.unsetKey(serveAt.toString())
                case e: Event.Overflow    => logger.info("overflow")
                case e: Event.NonStandard => logger.info("non-standard")
          )
    }
    .compile
    .drain
    .background
end fileWatcher
