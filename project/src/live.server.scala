// write a basic http4s server

import scala.concurrent.duration.*

import org.http4s.*
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.client.Client
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
import cats.effect.std.*
import cats.implicits.*

import _root_.io.circe.*
import _root_.io.circe.Encoder

import ProxyConfig.Equilibrium

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

  private val logger = scribe.cats[IO]

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
    def makeProxyRoutes(
        client: Client[IO],
        pathPrefix: Option[String],
        proxyConfig: Resource[IO, Option[Equilibrium]]
    ): Resource[IO, HttpRoutes[IO]] =
      proxyConfig.flatMap {
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

          fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
          fileToHashMapRef = MapRef.fromSingleImmutableMapRef[IO, String, String](fileToHashRef)
          refreshTopic <- Topic[IO, Unit].toResource
          linkingTopic <- Topic[IO, Unit].toResource
          client <- EmberClientBuilder.default[IO].build

          proxyRoutes: HttpRoutes[IO] <- makeProxyRoutes(client, pathPrefix, proxyConfig)

          _ <- buildRunner(
            buildTool,
            linkingTopic,
            fs2.io.file.Path(baseDir),
            fs2.io.file.Path(outDir),
            extraBuildArgs,
            millModuleName
          )(logger)

          indexHtmlTemplate = externalIndexHtmlTemplate.getOrElse(vanillaTemplate(stylesDir.isDefined).render)

          app <- routes(outDir.toString(), refreshTopic, stylesDir, proxyRoutes, indexHtmlTemplate, fileToHashRef)(
            logger
          )

          _ <- seedMapOnStart(outDir, fileToHashMapRef)(logger)
          // _ <- stylesDir.fold(Resource.unit)(sd => seedMapOnStart(sd, mr))
          _ <- fileWatcher(fs2.io.file.Path(outDir), fileToHashMapRef, linkingTopic, refreshTopic)(logger)
          // _ <- stylesDir.fold(Resource.unit[IO])(sd => fileWatcher(fs2.io.file.Path(sd), mr))
          _ <- logger.info(s"Start dev server on http://localhost:$port").toResource
          server <- buildServer(app, port)

          - <- openBrowser(openBrowserAt, port)(logger).toResource
        yield server

        server.use(_ => IO.never).as(ExitCode.Success)
    }
  end main
end LiveServer
