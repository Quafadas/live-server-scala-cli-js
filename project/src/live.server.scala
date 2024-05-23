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
              logger.trace(s"hashing $f") >>
                fielHash(f).flatMap(
                  h =>
                    val key = asFs2.relativize(f)
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
                          val serveAt = path.relativize(stringPath.toNioPath)
                          logger.trace(s"$serveAt :: hash -> $h") >>
                            mr.setKeyValue(serveAt.toString(), h)
                      )
                  // else IO.unit
                  case Event.Modified(path, i) =>
                    // if path.endsWith(".js") then
                    logger.trace(s"modified $path, calculating hash") >>
                      fielHash(fs2.io.file.Path(path.toString())).flatMap(
                        h =>
                          val serveAt = path.relativize(stringPath.toNioPath)
                          logger.trace(s"$serveAt :: hash -> $h") >>
                            mr.setKeyValue(serveAt.toString(), h)
                      )
                  // else IO.unit
                  case Event.Deleted(path, i) =>
                    val serveAt = path.relativize(stringPath.toNioPath)
                    logger.trace(s"deleted $path, removing key") >>
                      mr.unsetKey(serveAt.toString())
                  case e: Event.Overflow    => logger.info("overflow")
                  case e: Event.NonStandard => logger.info("non-standard")
            )
      }
      .compile
      .drain
      .background
  end fileWatcher

  def routes(
      stringPath: String,
      refreshTopic: Topic[IO, String],
      stylesPath: Option[String],
      proxyRoutes: HttpRoutes[IO]
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

        val overrides = HttpRoutes.of[IO] {
          case GET -> Root =>
            logger.trace("GET /") >>
              ref.get.flatMap(mp => logger.trace(mp.toString())) >>
              Ok(
                (ref
                  .get
                  .map(_.toSeq.map((path, hash) => (fs2.io.file.Path(path), hash)))
                  .map(mods => makeHeader(mods, stylesPath.isDefined)))
              )
          case GET -> Root / "index.html" =>
            Ok(
              (ref
                .get
                .map(_.toSeq.map((path, hash) => (fs2.io.file.Path(path), hash)))
                .map(mods => makeHeader(mods, stylesPath.isDefined)))
            )
          case GET -> Root / "all" =>
            ref
              .get
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

        (overrides.combineK(staticFiles).combineK(styles).combineK(proxyRoutes).orNotFound, mr, ref)
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
      openBrowserAtOpt
    ).mapN {
      (baseDir, outDir, stylesDir, port, proxyTarget, pathPrefix, lvl, buildTool, openBrowserAt) =>

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
              s"baseDir: $baseDir \n outDir: $outDir \n stylesDir: $stylesDir \n port: $port \n proxyTarget: $proxyTarget \n pathPrefix: $pathPrefix"
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

          _ <- logger.info(s"Start dev server on https://localhost:$port").toResource

          refreshPub <- refreshTopic

          _ <- buildRunner(buildTool, refreshPub, fs2.io.file.Path(baseDir), fs2.io.file.Path(outDir))(logger)

          routes <- routes(outDir.toString(), refreshPub, stylesDir, proxyRoutes)

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
