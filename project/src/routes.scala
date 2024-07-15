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
import org.http4s.Uri.Path.SegmentEncoder
import org.http4s.dsl.io.*
import org.http4s.scalatags.*
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
import org.http4s.server.staticcontent.*
import org.http4s.server.staticcontent.FileService
import org.typelevel.ci.CIStringSyntax
import org.http4s.EntityBody

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
import org.http4s.Http

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

        case req @ GET -> Root / fName ~ "map" =>
          StaticFile
            .fromPath(fs2.io.file.Path(stringPath) / req.uri.path.renderString, Some(req))
            .getOrElseF(NotFound())

      },
      ref
    )(logger)

  // val hashFalse = vanillaTemplate(false).render.hashCode.toString
  // val hashTrue = vanillaTemplate(true).render.hashCode.toString
  val zdt = ZonedDateTime.now()

  def userBrowserCacheHeaders(resp: Response[IO], lastModZdt: ZonedDateTime, injectStyles: Boolean) =
    val hash = resp.body.through(fs2.hash.md5).through(fs2.text.hex.encode).compile.string
    hash.map: h =>
      resp.putHeaders(
        Header.Raw(ci"Cache-Control", "no-cache"),
        Header.Raw(
          ci"ETag",
          h
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
  end userBrowserCacheHeaders

  object StaticHtmlMiddleware:
    def apply(service: HttpRoutes[IO], injectStyles: Boolean)(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
      (req: Request[IO]) =>
        service(req).semiflatMap(userBrowserCacheHeaders(_, zdt, injectStyles))
    }

  end StaticHtmlMiddleware

  def generatedIndexHtml(injectStyles: Boolean, modules: Ref[IO, Map[String, String]]) =
    StaticHtmlMiddleware(
      HttpRoutes.of[IO] {
        case req @ GET -> Root =>
          logger.trace("Generated index.html") >>
            vanillaTemplate(injectStyles, modules).map: html =>
              Response[IO]()
                .withEntity(html)
                .withHeaders(
                  Header.Raw(ci"Cache-Control", "no-cache"),
                  Header.Raw(
                    ci"ETag",
                    html.hashCode.toString
                  ),
                  Header.Raw(ci"Last-Modified", formatter.format(zdt)),
                  Header.Raw(
                    ci"Expires",
                    httpCacheFormat(ZonedDateTime.ofInstant(Instant.now().plusSeconds(10000000), ZoneId.of("GMT")))
                  )
                )

      },
      injectStyles
    )(logger).combineK(
      StaticHtmlMiddleware(
        HttpRoutes.of[IO] {
          case GET -> Root / "index.html" =>
            vanillaTemplate(injectStyles, modules).map: html =>
              Response[IO]().withEntity(html)

        },
        injectStyles
      )(logger)
    )

  // val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
  def staticAssetRoutes(modules: Ref[IO, Map[String, String]]): HttpRoutes[IO] = indexOpts match
    case None => generatedIndexHtml(injectStyles = false, modules)

    case Some(IndexHtmlConfig.IndexHtmlPath(path)) =>
      // StaticMiddleware(
      // Router(
      //   "" ->
      HttpRoutes
        .of[IO] {
          case req @ GET -> Root =>
            StaticFile
              .fromPath[IO](path / "index.html")
              .getOrElseF(NotFound())
              .flatMap {
                f =>
                  f.body
                    .through(text.utf8.decode)
                    .compile
                    .string
                    .flatMap {
                      body =>
                        for str <- injectModulePreloads(modules, body)
                        yield
                          val bytes = str.getBytes()
                          f.withEntity(bytes)
                          Response[IO]().withEntity(bytes).putHeaders("Content-Type" -> "text/html")

                    }
              }

        }
        .combineK(
          StaticMiddleware(
            Router(
              "" -> fileService[IO](FileService.Config(path.toString()))
            ),
            fs2.io.file.Path(path.toString())
          )(logger)
        )

    case Some(IndexHtmlConfig.StylesOnly(stylesPath)) =>
      NoCacheMiddlware(
        Router(
          "" -> fileService[IO](FileService.Config(stylesPath.toString()))
        )
      )(logger).combineK(generatedIndexHtml(injectStyles = true, modules))

  def clientSpaRoutes(modules: Ref[IO, Map[String, String]]): HttpRoutes[IO] =
    clientRoutingPrefix match
      case None => HttpRoutes.empty[IO]
      case Some(spaRoute) =>
        val r = indexOpts match
          case None =>
            Root / spaRoute
            StaticHtmlMiddleware(
              HttpRoutes.of[IO] {
                case req @ GET -> root /: path =>
                  vanillaTemplate(false, modules).map: html =>
                    Response[IO]().withEntity(html)

              },
              false
            )(logger)

          case Some(IndexHtmlConfig.StylesOnly(dir)) =>
            StaticHtmlMiddleware(
              HttpRoutes.of[IO] {
                case GET -> root /: spaRoute /: path =>
                  vanillaTemplate(true, modules).map: html =>
                    Response[IO]().withEntity(html)
              },
              true
            )(logger)

          case Some(IndexHtmlConfig.IndexHtmlPath(dir)) =>
            StaticFileMiddleware(
              HttpRoutes.of[IO] {
                case req @ GET -> spaRoute /: path =>
                  StaticFile
                    .fromPath(dir / "index.html", Some(req))
                    .getOrElseF(NotFound())
                    .flatMap {
                      f =>
                        f.body
                          .through(text.utf8.decode)
                          .compile
                          .string
                          .flatMap: body =>
                            for str <- injectModulePreloads(modules, body)
                            yield
                              val bytes = str.getBytes()
                              f.withEntity(bytes)
                              f

                    }

              },
              dir / "index.html"
            )(logger)

        Router(spaRoute -> r)

  val refreshRoutes = HttpRoutes.of[IO] {
    case GET -> Root / "refresh" / "v1" / "sse" =>
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
      .combineK(clientSpaRoutes(ref))
      .combineK(staticAssetRoutes(ref))
  )

  clientRoutingPrefix.fold(IO.unit)(s => logger.trace(s"client spa at : $s")).toResource >>
    IO(app).toResource

end routes
