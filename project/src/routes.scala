import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import scala.concurrent.duration.DurationInt

import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.ServerSentEvent
import org.http4s.StaticFile
import org.http4s.Status
import org.http4s.dsl.io.*
import org.http4s.scalatags.*
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
import org.http4s.server.staticcontent.*
import org.http4s.server.staticcontent.FileService
import org.typelevel.ci.CIStringSyntax

import fs2.*
import fs2.concurrent.Topic
import fs2.io.file.Files

import scribe.Scribe

import cats.MonadThrow
import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*

import _root_.io.circe.syntax.EncoderOps
import org.http4s.Uri.Path.SegmentEncoder

def routes[F[_]: Files: MonadThrow](
    stringPath: String,
    refreshTopic: Topic[IO, Unit],
    indexOpts: Option[IndexHtmlConfig],
    proxyRoutes: HttpRoutes[IO],
    ref: Ref[IO, Map[String, String]],
    clientRoutingPrefix: Option[String]
)(logger: Scribe[IO]): Resource[IO, HttpRoutes[IO]] =

  val logMiddler = Logger.httpRoutes[IO](
    logHeaders = true,
    logBody = true,
    redactHeadersWhen = _ => false,
    logAction = Some((msg: String) => logger.trace(msg))
  )

  val linkedAppWithCaching: HttpRoutes[IO] =
    ETagMiddleware(
      HttpRoutes.of[IO] {
        case req @ GET -> Root / fName ~ "js" =>
          StaticFile
            .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
            .getOrElseF(NotFound())
      },
      ref
    )(logger)

  val hashFalse = vanillaTemplate(false).render.hashCode.toString
  val hashTrue = vanillaTemplate(true).render.hashCode.toString
  val zdt = ZonedDateTime.now()

  def userBrowserCacheHeaders(resp: Response[IO], lastModZdt: ZonedDateTime, injectStyles: Boolean) =
    resp.putHeaders(
      Header.Raw(ci"Cache-Control", "no-cache"),
      Header.Raw(
        ci"ETag",
        injectStyles match
          case true  => hashTrue
          case false => hashFalse
      ),
      Header.Raw(
        ci"Last-Modified",
        formatter.format(lastModZdt)
      ),
      Header.Raw(
        ci"Expires",
        httpCacheFormat(ZonedDateTime.ofInstant(Instant.now().plusSeconds(10000000), ZoneId.of("GMT")))
      )
    )
    resp
  end userBrowserCacheHeaders

  object StaticHtmlMiddleware:
    def apply(service: HttpRoutes[IO], injectStyles: Boolean)(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
      (req: Request[IO]) =>
        req.headers.get(ci"If-None-Match").map(_.toList) match
          case Some(h :: Nil) if h.value == hashFalse => OptionT.liftF(IO(Response[IO](Status.NotModified)))
          case Some(h :: Nil) if h.value == hashTrue  => OptionT.liftF(IO(Response[IO](Status.NotModified)))
          case _                                      => service(req).map(userBrowserCacheHeaders(_, zdt, injectStyles))
        end match

    }

  end StaticHtmlMiddleware

  def generatedIndexHtml(injectStyles: Boolean) =
    StaticHtmlMiddleware(
      HttpRoutes.of[IO] {
        case req @ GET -> Root =>
          logger.trace("Generated index.html") >>
            IO(
              Response[IO]()
                .withEntity(vanillaTemplate(injectStyles))
                .withHeaders(
                  Header.Raw(ci"Cache-Control", "no-cache"),
                  Header.Raw(
                    ci"ETag",
                    injectStyles match
                      case true  => hashTrue
                      case false => hashFalse
                  ),
                  Header.Raw(ci"Last-Modified", formatter.format(zdt)),
                  Header.Raw(
                    ci"Expires",
                    httpCacheFormat(ZonedDateTime.ofInstant(Instant.now().plusSeconds(10000000), ZoneId.of("GMT")))
                  )
                )
            )
      },
      injectStyles
    )(logger).combineK(
      StaticHtmlMiddleware(
        HttpRoutes.of[IO] {
          case GET -> Root / "index.html" =>
            IO {
              Response[IO]().withEntity(vanillaTemplate(injectStyles))
            }
        },
        injectStyles
      )(logger)
    )

  // val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
  val staticAssetRoutes: HttpRoutes[IO] = indexOpts match
    case None => generatedIndexHtml(injectStyles = false)

    case Some(IndexHtmlConfig.IndexHtmlPath(path)) =>
      StaticMiddleware(
        Router(
          "" -> fileService[IO](FileService.Config(path.toString()))
        ),
        fs2.io.file.Path(path.toString())
      )(logger)

    case Some(IndexHtmlConfig.StylesOnly(stylesPath)) =>
      NoCacheMiddlware(
        Router(
          "" -> fileService[IO](FileService.Config(stylesPath.toString()))
        )
      )(logger).combineK(generatedIndexHtml(injectStyles = true))

  val clientSpaRoutes: HttpRoutes[IO] =
    clientRoutingPrefix match
      case None => HttpRoutes.empty[IO]
      case Some(spaRoute) =>
        val r = indexOpts match
          case None =>
            Root / spaRoute
            StaticHtmlMiddleware(
              HttpRoutes.of[IO] {
                case req @ GET -> root /: path =>
                  IO(
                    Response[IO]().withEntity(vanillaTemplate(false))
                  )
              },
              false
            )(logger)

          case Some(IndexHtmlConfig.StylesOnly(dir)) =>
            StaticHtmlMiddleware(
              HttpRoutes.of[IO] {
                case GET -> root /: spaRoute /: path =>
                  IO(
                    Response[IO]().withEntity(vanillaTemplate(true))
                  )
              },
              true
            )(logger)

          case Some(IndexHtmlConfig.IndexHtmlPath(dir)) =>
            StaticFileMiddleware(
              HttpRoutes.of[IO] {
                case req @ GET -> spaRoute /: path =>
                  StaticFile.fromPath(dir / "index.html", Some(req)).getOrElseF(NotFound())
              },
              dir / "index.html"
            )(logger)

        Router(s"/$spaRoute" -> r)

  val refreshRoutes = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "v1" / "sse" =>
      val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
      Ok(
        keepAlive
          .merge(refreshTopic.subscribe(10).as(PageRefresh()))
          .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
      )
  }
  val app = logMiddler(
    refreshRoutes
      .combineK(linkedAppWithCaching)
      .combineK(proxyRoutes)
      .combineK(clientSpaRoutes)
      .combineK(staticAssetRoutes)
  )

  clientRoutingPrefix.fold(IO.unit)(s => logger.trace(s"client spa at : $s")).toResource >>
    IO(app).toResource

end routes
