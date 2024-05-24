// write a basic http4s server

import scala.concurrent.duration.*

import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.ServerSentEvent
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.scalatags.*
import org.http4s.server.Router
import org.http4s.server.staticcontent.*

import com.comcast.ip4s.Port
import com.comcast.ip4s.host
import com.monovore.decline.*
import com.monovore.decline.effect.*

import fs2.*
import fs2.concurrent.Topic
import fs2.io.Watcher
import fs2.io.Watcher.Event
import fs2.io.file.Files

import scribe.Level

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.*
import cats.effect.std.*
import cats.implicits.*

import _root_.io.circe.*
import _root_.io.circe.Encoder
import _root_.io.circe.syntax.*

import ProxyConfig.Equilibrium
import java.awt.Desktop
import java.net.URI
import org.http4s.Header

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax

sealed trait FrontendEvent derives Encoder.AsObject

case class KeepAlive() extends FrontendEvent derives Encoder.AsObject
case class PageRefresh() extends FrontendEvent derives Encoder.AsObject

def makeProxyConfig(frontendPort: Port, proxyTo: Port, matcher: String) = s"""
http:
  servers:
    - listen: $frontendPort
      serverNames:
        - localhost
      locations:
        - matcher: $matcher
          proxyPass: http://$$backend

  upstreams:
    - name: backend
      servers:
        - host: localhost
          port: $proxyTo
          weight: 5
"""

object LiveServer
    extends CommandIOApp(
      name = "LiveServer",
      header = "Scala JS live server",
      version = "0.0.1"
    ):

  private val refreshTopic = Topic[IO, String].toResource
  private val logger = scribe.cats[IO]
  // val logger = scribe.cats[IO]

  private def seedMapOnStart(stringPath: String, mr: MapRef[IO, String, Option[String]]) =
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
  ): ResourceIO[IO[OutcomeIO[Unit]]] =
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

  object ETagMiddleware:

    def apply(service: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli {
      (req: Request[IO]) =>
        req.headers.get(ci"If-None-Match") match
          case Some(header) =>
            req.uri.query.params.get("hash") match
              case Some(hash) =>
                OptionT.liftF(logger.debug(s"Hash  : $hash")) >>
                  service(req)
              case None =>
                OptionT.liftF(logger.debug("No hash in query")) >>
                  OptionT.liftF(NotModified())
          case _ =>
            OptionT.liftF(logger.debug("No headers in query")) >>
              service(req)
    }
  end ETagMiddleware

  def routes(
      stringPath: String,
      refreshTopic: Topic[IO, String],
      stylesPath: Option[String],
      proxyRoutes: HttpRoutes[IO],
      indexHtmlTemplate: String
  ): Resource[IO, (HttpApp[IO], MapRef[IO, String, Option[String]], Ref[IO, Map[String, String]])] =
    Resource.eval(
      for
        ref <- Ref[IO].of(Map.empty[String, String])
        mr = MapRef.fromSingleImmutableMapRef[IO, String, String](ref)
      yield
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
            .map(html => Response[IO]().withEntity(html).withHeaders(Header("Cache-Control", "no-cache")))

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
        val app = overrides.combineK(staticFiles).combineK(styles).combineK(proxyRoutes).orNotFound
        (app, mr, ref)
    )
  end routes

  private def buildServer(httpApp: HttpApp[IO], port: Port) = EmberServerBuilder
    .default[IO]
    .withHttp2
    .withHost(host"localhost")
    .withPort(port)
    .withHttpApp(httpApp)
    .withShutdownTimeout(10.milli)
    .build

  // override def main: Opts[IO[ExitCode]] =
  //   (showProcessesOpts orElse buildOpts).map {
  //     case ShowProcesses(all)           => ???
  //     case BuildImage(dockerFile, path) => ???
  //   }

  val logLevelOpt: Opts[String] = Opts
    .option[String]("log-level", help = "The log level (e.g., info, debug, error)")
    .withDefault("info")
    .validate("Invalid log level") {
      case "info"  => true
      case "debug" => true
      case "error" => true
      case "warn"  => true
      case "trace" => true
      case _       => false
    }

  val openBrowserAtOpt =
    Opts
      .option[String](
        "browse-on-open-at",
        "A suffix to localhost where we'll open a browser window on server start - e.g. /ui/greatPage OR just `/` for root "
      )
      .orNone

  val baseDirOpt =
    Opts
      .option[String]("project-dir", "The fully qualified location of your project - e.g. c:/temp/helloScalaJS")
      .withDefault(os.pwd.toString())
      .validate("The project directory should be a fully qualified directory")(s => os.isDir(os.Path(s)))

  val outDirOpt = Opts
    .option[String]("out-dir", "Where the compiled JS will be compiled to - e.g. c:/temp/helloScalaJS/.out")
    .withDefault((os.pwd / ".out").toString())
    .validate("Must be a directory")(s => os.isDir(os.Path(s)))

  val stylesDirOpt = Opts
    .option[String](
      "styles-dir",
      "A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles"
    )
    .orNone
    .validate("Must be a directory")(sOpt => sOpt.fold(true)(s => os.isDir(os.Path(s))))

  val portOpt = Opts
    .option[Int]("port", "The port yo want to run the server on - e.g. 3000")
    .withDefault(3000)
    .validate("Port must be between 1 and 65535")(i => i > 0 && i < 65535)
    .map(i => Port.fromInt(i).get)

  val proxyPortTargetOpt = Opts
    .option[Int]("proxy-target-port", "The port you want to forward api requests to - e.g. 8080")
    .orNone
    .validate("Proxy Port must be between 1 and 65535")(iOpt => iOpt.fold(true)(i => i > 0 && i < 65535))
    .map(i => i.flatMap(Port.fromInt))

  val proxyPathMatchPrefixOpt = Opts
    .option[String]("proxy-prefix-path", "Match routes starting with this prefix - e.g. /api")
    .orNone

  val buildToolOpt = Opts
    .option[String]("build-tool", "scala-cli or mill")
    .validate("Invalid build tool") {
      case "scala-cli" => true
      case "mill"      => true
      case _           => false
    }
    .map {
      _ match
        case "scala-cli" => ScalaCli()
        case "mill"      => Mill()
    }

  val extraBuildArgsOpt: Opts[List[String]] = Opts
    .options[String](
      "extra-build-args",
      "Extra arguments to pass to the build tool"
    )
    .orEmpty

  val indexHtmlTemplateOpt: Opts[Option[String]] = Opts
    .option[String](
      "path-to-index-html-template",
      "a path to a file which contains the index.html template you want to use. \n" +
        "The file _MUST_ have the EXACT string <script __REPLACE_WITH_MODULE_HEADERS__\\> in the header tag/>"
    )
    .validate(
      "index.html must be a file, with a .html extension, and must contain <head> </head> and <body> </body> tags"
    ) {
      path =>
        os.isFile(os.Path(path)) match
          case false => false
          case true =>
            val f = os.Path(path)
            f.ext match
              case "html" =>
                val content = os.read(f)
                content.contains("</head>") && content.contains("</body>") && content.contains("<head>") && content
                  .contains(
                    "<body>"
                  )
              case _ => false
            end match

    }
    .map {
      path =>
        os.read(os.Path(path))
    }
    .orNone

  val millModuleNameOpt: Opts[Option[String]] = Opts
    .option[String](
      "mill-module-name",
      "Extra arguments to pass to the build tool"
    )
    .validate("mill module name cannot be blank") {
      case "" => false
      case _  => true
    }
    .orNone

  override def main: Opts[IO[ExitCode]] =
    given R: Random[IO] = Random.javaUtilConcurrentThreadLocalRandom[IO]
    (
      baseDirOpt,
      outDirOpt,
      stylesDirOpt,
      portOpt,
      proxyPortTargetOpt,
      proxyPathMatchPrefixOpt,
      logLevelOpt,
      buildToolOpt,
      openBrowserAtOpt,
      extraBuildArgsOpt,
      millModuleNameOpt,
      indexHtmlTemplateOpt
    ).mapN {
      (
          baseDir,
          outDir,
          stylesDir,
          port,
          proxyTarget,
          pathPrefix,
          lvl,
          buildTool,
          openBrowserAt,
          extraBuildArgs,
          millModuleName,
          externalIndexHtmlTemplate
      ) =>

        scribe
          .Logger
          .root
          .clearHandlers()
          .clearModifiers()
          .withHandler(minimumLevel = Some(Level.get(lvl).get))
          .replace()

        val proxyConfig: Resource[IO, Option[Equilibrium]] = proxyTarget
          .zip(pathPrefix)
          .traverse {
            (pt, prfx) =>
              ProxyConfig.loadYaml[IO](makeProxyConfig(port, pt, prfx)).toResource
          }

        val server = for
          _ <- logger
            .debug(
              s"baseDir: $baseDir \n outDir: $outDir \n stylesDir: $stylesDir \n port: $port \n proxyTarget: $proxyTarget \n pathPrefix: $pathPrefix \n extraBuildArgs: $extraBuildArgs"
            )
            .toResource

          client <- EmberClientBuilder.default[IO].build

          proxyRoutes: HttpRoutes[IO] <- proxyConfig.flatMap {
            case Some(pc) =>
              {
                logger.debug("setup proxy server") >>
                  IO(HttpProxy.servers[IO](pc, client, pathPrefix.getOrElse(???)).head._2)
              }.toResource

            case None =>
              (
                logger.debug("no proxy set") >>
                  IO(HttpRoutes.empty[IO])
              ).toResource
          }

          _ <- logger.info(s"Start dev server on http://localhost:$port").toResource

          refreshPub <- refreshTopic

          _ <- buildRunner(
            buildTool,
            refreshPub,
            fs2.io.file.Path(baseDir),
            fs2.io.file.Path(outDir),
            extraBuildArgs,
            millModuleName
          )(logger)
          indexHtmlTemplate = externalIndexHtmlTemplate.getOrElse(vanillaTemplate(stylesDir.isDefined).render)

          routes <- routes(outDir.toString(), refreshPub, stylesDir, proxyRoutes, indexHtmlTemplate)

          (app, mr, ref) = routes

          _ <- seedMapOnStart(outDir, mr)
          // _ <- stylesDir.fold(Resource.unit)(sd => seedMapOnStart(sd, mr))
          _ <- fileWatcher(fs2.io.file.Path(outDir), mr)
          // _ <- stylesDir.fold(Resource.unit[IO])(sd => fileWatcher(fs2.io.file.Path(sd), mr))
          server <- buildServer(app, port)

          - <- IO {
            openBrowserAt match
              case None => logger.trace("No openBrowserAt flag set, so no browser will be opened")
              case Some(value) =>
                val openAt = URI(s"http://localhost:$port$value")
                logger.info(s"Attemptiong to open browser to $openAt") >>
                  IO(
                    if Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) then
                      IO(Desktop.getDesktop().browse(openAt))
                    else logger.error("Desktop not supported, so can't open browser")
                  ).flatten
          }.flatten.toResource
        yield server

        server.use(_ => IO.never).as(ExitCode.Success)
    }
  end main
end LiveServer
