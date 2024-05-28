import scala.concurrent.duration.*

import org.http4s.*
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

import com.comcast.ip4s.Port
import com.comcast.ip4s.host
import com.monovore.decline.*
import com.monovore.decline.effect.*

import fs2.*
import fs2.concurrent.Topic

import scribe.Level

import cats.effect.*
import cats.implicits.*

import _root_.io.circe.*
import _root_.io.circe.Encoder

import ProxyConfig.Equilibrium
import org.http4s.server.Router
import org.http4s.server.staticcontent.fileService
import org.http4s.server.staticcontent.FileService

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

case class IndexHtmlConfig(
    indexHtmlatPath: Option[IndexHtmlDir],
    stylesOnly: Option[StyleDir]
)

type StyleDir = os.Path
type IndexHtmlDir = os.Path
type ProxyPrefix = String
type ClientSideRoutesPrefix = String

object LiveServer
    extends CommandIOApp(
      name = "LiveServer",
      header = "Scala JS live server",
      version = "0.0.1"
    ):

  private val logger = scribe.cats[IO]

  private def buildServer(httpApp: HttpApp[IO], port: Port) = EmberServerBuilder
    .default[IO]
    .withHttp2
    .withHost(host"localhost")
    .withPort(port)
    .withHttpApp(httpApp)
    .withShutdownTimeout(10.milli)
    .build

  val logLevelOpt: Opts[String] = Opts
    .option[String]("log-level", help = "The log level. info, debug, error, trace)")
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
      .withDefault("/")

  val baseDirOpt =
    Opts
      .option[String]("project-dir", "The fully qualified location of your project - e.g. c:/temp/helloScalaJS")
      .withDefault(os.pwd.toString())
      .validate("The project directory should be a fully qualified directory")(s => os.isDir(os.Path(s)))

  val outDirOpt = Opts
    .option[String](
      "out-dir",
      "Where the compiled JS will be compiled to - e.g. c:/temp/helloScalaJS/.out. If no file is given, a temporary directory is created."
    )
    .withDefault(
      os.temp.dir().toString()
    )
    .validate("Must be a directory")(s => os.isDir(os.Path(s)))

  val portOpt = Opts
    .option[Int]("port", "The port you want to run the server on - e.g. 3000")
    .withDefault(3000)
    .validate("Port must be between 1 and 65535")(i => i > 0 && i < 65535)
    .map(i => Port.fromInt(i).get)

  val proxyPortTargetOpt = Opts
    .option[Int]("proxy-target-port", "The port you want to forward api requests to - e.g. 8080")
    .orNone
    .validate("Proxy Port must be between 1 and 65535")(iOpt => iOpt.fold(true)(i => i > 0 && i < 65535))
    .map(i => i.flatMap(Port.fromInt))

  val proxyPathMatchPrefixOpt = Opts
    .option[ProxyPrefix]("proxy-prefix-path", "Match routes starting with this prefix - e.g. /api")
    .orNone

  val clientRoutesPrefixOpt = Opts
    .option[ClientSideRoutesPrefix](
      "client-side-routes-prefix",
      "Match routes starting with this prefix - e.g. /app. The server will respond with the index.html file - this enables clientside routing, at any URL below this prefix."
    )
    .orNone

  val buildToolOpt = Opts
    .option[String]("build-tool", "scala-cli or mill")
    .validate("Invalid build tool") {
      case "scala-cli" => true
      case "mill"      => true
      case _           => false
    }
    .withDefault("scala-cli")
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

  val stylesDirOpt: Opts[Option[StyleDir]] = Opts
    .option[String](
      "styles-dir",
      "A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles"
    )
    .orNone
    .validate("The styles-dir must be a directory, it should have index.less at it's root")(
      sOpt => sOpt.fold(true)(s => os.isDir(os.Path(s)))
    )
    .map(_.map(os.Path(_)))

  val indexHtmlTemplateOpt: Opts[Option[IndexHtmlDir]] = Opts
    .option[String](
      "path-to-index-html",
      "a path to a directory which contains index.html. The entire directory will be served as static assets"
    )
    .orNone
    .validate(
      "The path-to-index-html must be a directory. The directory must contain an index.html file. index.html must contain <head> </head> and <body> </body> tags"
    ) {
      pathOpt =>
        pathOpt.forall {
          path =>
            os.isDir(os.Path(path)) match
              case false => false
              case true =>
                val f = os.Path(path) / "index.html"
                os.exists(f) match
                  case false => false
                  case true =>
                    val content = os.read(f)
                    content.contains("</head>") && content.contains("</body>") && content.contains("<head>") && content
                      .contains(
                        "<body>"
                      )
                end match
        }

    }
    .map(_.map(os.Path(_)))

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

  val indexOpts = (indexHtmlTemplateOpt, stylesDirOpt)
    .mapN(IndexHtmlConfig.apply)
    .validate("You must provide either a styles directory or an index.html template directory, or neither") {
      c => c.indexHtmlatPath.isDefined || c.stylesOnly.isDefined || (c.indexHtmlatPath.isEmpty && c.stylesOnly.isEmpty)
    }
    .map(
      c =>
        c match
          case IndexHtmlConfig(None, None) => None
          case _                           => Some(c)
    )

  override def main: Opts[IO[ExitCode]] =
    (
      baseDirOpt,
      outDirOpt,
      portOpt,
      proxyPortTargetOpt,
      proxyPathMatchPrefixOpt,
      clientRoutesPrefixOpt,
      logLevelOpt,
      buildToolOpt,
      openBrowserAtOpt,
      extraBuildArgsOpt,
      millModuleNameOpt,
      indexOpts
    ).mapN {
      (
          baseDir,
          outDir,
          port,
          proxyTarget,
          pathPrefix,
          clientRoutesPrefix,
          lvl,
          buildTool,
          openBrowserAt,
          extraBuildArgs,
          millModuleName,
          indexOpts
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
              s"baseDir: $baseDir \n outDir: $outDir \n indexOpts: $indexOpts \n port: $port \n proxyTarget: $proxyTarget \n pathPrefix: $pathPrefix \n extraBuildArgs: $extraBuildArgs"
            )
            .toResource

          fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
          refreshTopic <- Topic[IO, Unit].toResource
          linkingTopic <- Topic[IO, Unit].toResource
          client <- EmberClientBuilder.default[IO].build
          outDirPath = fs2.io.file.Path(outDir)
          baseDirPath = fs2.io.file.Path(baseDir)

          proxyRoutes: HttpRoutes[IO] <- makeProxyRoutes(client, pathPrefix, proxyConfig)(logger)

          clientRoutes: HttpRoutes[IO] <- (clientRoutesPrefix, indexOpts) match
            case (None, _) => Resource.pure(HttpRoutes.empty[IO])

            case (Some(prefix), Some(IndexHtmlConfig(None, Some(stylesPath)))) =>
              Resource.pure(HttpRoutes.of[IO] {
                case req @ GET -> Root / prefix                          => vanillaIndexResponse(true)
                case req @ _ -> path if path.toString.startsWith(prefix) => vanillaIndexResponse(true)
              })

            case (Some(prefix), Some(IndexHtmlConfig(None, None))) =>
              Resource.pure(HttpRoutes.of[IO] {
                case req @ GET -> Root / prefix                          => vanillaIndexResponse(false)
                case req @ _ -> path if path.toString.startsWith(prefix) => vanillaIndexResponse(false)
              })

            case (Some(prefix), Some(IndexHtmlConfig(Some(indexHtmlPath), None))) =>
              Resource.pure(HttpRoutes.of[IO] {
                case req @ GET -> Root / prefix =>
                  StaticFile
                    .fromPath[IO](fs2.io.file.Path(indexHtmlPath.toString()) / "index.html")
                    .map(_.withHeaders(Header("Cache-Control", "no-cache")))
                    .getOrElseF(NotFound())
                case req @ _ -> path if path.toString.startsWith(prefix) =>
                  StaticFile
                    .fromPath[IO](fs2.io.file.Path(indexHtmlPath.toString()) / "index.html")
                    .map(_.withHeaders(Header("Cache-Control", "no-cache")))
                    .getOrElseF(NotFound())
              })

            case (Some(prefix), Some(IndexHtmlConfig(Some(indexHtmlPath), Some(stylesPath)))) => ???

          _ <- buildRunner(
            buildTool,
            linkingTopic,
            baseDirPath,
            outDirPath,
            extraBuildArgs,
            millModuleName
          )(logger)

          app <- routes(outDir, refreshTopic, indexOpts, proxyRoutes, fileToHashRef)(logger)

          _ <- updateMapRef(outDirPath, fileToHashRef)(logger).toResource
          // _ <- stylesDir.fold(Resource.unit)(sd => seedMapOnStart(sd, mr))
          _ <- fileWatcher(outDirPath, fileToHashRef, linkingTopic, refreshTopic)(logger)
          // _ <- stylesDir.fold(Resource.unit[IO])(sd => fileWatcher(fs2.io.file.Path(sd), mr))
          _ <- logger.info(s"Start dev server on http://localhost:$port").toResource
          server <- buildServer(app, port)

          - <- openBrowser(Some(openBrowserAt), port)(logger).toResource
        yield server

        server.use(_ => IO.never).as(ExitCode.Success)
    }
  end main
end LiveServer
