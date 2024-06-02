import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import org.http4s.*
import org.http4s.HttpApp
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

import com.comcast.ip4s.Port
import com.comcast.ip4s.host
import com.monovore.decline.*
import com.monovore.decline.effect.*

import fs2.*
import fs2.concurrent.Topic
import fs2.io.file.*

import scribe.Level

import cats.effect.*
import cats.implicits.*

import _root_.io.circe.*
import _root_.io.circe.Encoder

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

enum IndexHtmlConfig:
  case IndexHtmlPath(path: Path)
  case StylesOnly(path: Path)
end IndexHtmlConfig

case class CliValidationError(message: String) extends NoStackTrace

object LiveServer extends IOApp:
  private val logger = scribe.cats[IO]
  given filesInstance: Files[IO] = Files.forAsync[IO]

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
      .orNone

  val outDirOpt = Opts
    .option[String](
      "out-dir",
      "Where the compiled JS will be compiled to - e.g. c:/temp/helloScalaJS/.out. If no file is given, a temporary directory is created."
    )
    .orNone

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
    .option[String]("proxy-prefix-path", "Match routes starting with this prefix - e.g. /api")
    .orNone

  val clientRoutingPrefixOpt = Opts
    .option[String](
      "client-routes-prefix",
      "Routes starting with this prefix  e.g. /app will return index.html. This enables client side routing via e.g. waypoint"
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

  val stylesDirOpt: Opts[Option[String]] = Opts
    .option[String](
      "styles-dir",
      "A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles"
    )
    .orNone

  val indexHtmlTemplateOpt: Opts[Option[String]] = Opts
    .option[String](
      "path-to-index-html",
      "a path to a directory which contains index.html. The entire directory will be served as static assets"
    )
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

  def main: Opts[IO[ExitCode]] =
    (
      baseDirOpt,
      outDirOpt,
      portOpt,
      proxyPortTargetOpt,
      proxyPathMatchPrefixOpt,
      clientRoutingPrefixOpt,
      logLevelOpt,
      buildToolOpt,
      openBrowserAtOpt,
      extraBuildArgsOpt,
      millModuleNameOpt,
      stylesDirOpt,
      indexHtmlTemplateOpt
    ).mapN {
      (
          baseDir,
          outDirOpt,
          port,
          proxyTarget,
          pathPrefix,
          clientRoutingPrefix,
          lvl,
          buildTool,
          openBrowserAt,
          extraBuildArgs,
          millModuleName,
          stylesDir,
          indexHtmlTemplate
      ) =>

        scribe
          .Logger
          .root
          .clearHandlers()
          .clearModifiers()
          .withHandler(minimumLevel = Some(Level.get(lvl).get))
          .replace()

        val server = for
          _ <- logger
            .debug(
              s"baseDir: $baseDir \n outDir: $outDirOpt \n stylesDir: $stylesDir \n indexHtmlTemplate: $indexHtmlTemplate \n port: $port \n proxyTarget: $proxyTarget \n pathPrefix: $pathPrefix \n extraBuildArgs: $extraBuildArgs"
            )
            .toResource

          fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
          refreshTopic <- Topic[IO, Unit].toResource
          linkingTopic <- Topic[IO, Unit].toResource
          client <- EmberClientBuilder.default[IO].build
          baseDirPath <- baseDir.fold(Files[IO].currentWorkingDirectory.toResource)(toDirectoryPath)
          outDirPath <- outDirOpt.fold(Files[IO].tempDirectory)(toDirectoryPath)
          outDirString = outDirPath.show
          indexHtmlTemplatePath <- indexHtmlTemplate.traverse(toDirectoryPath)
          stylesDirPath <- stylesDir.traverse(toDirectoryPath)

          indexOpts <- (indexHtmlTemplatePath, stylesDirPath) match
            case (Some(html), None) =>
              val indexHtmlFile = html / "index.html"
              println(indexHtmlFile)
              (for
                indexHtmlExists <- Files[IO].exists(indexHtmlFile)
                _ <- IO.raiseUnless(indexHtmlExists)(CliValidationError(s"index.html doesn't exist in $html"))
                indexHtmlIsAFile <- Files[IO].isRegularFile(indexHtmlFile)
                _ <- IO.raiseUnless(indexHtmlIsAFile)(CliValidationError(s"$indexHtmlFile is not a file"))
              yield IndexHtmlConfig.IndexHtmlPath(html).some).toResource
            case (None, Some(styles)) =>
              val indexLessFile = styles / "index.less"
              (for
                indexLessExists <- Files[IO].exists(indexLessFile)
                _ <- IO.raiseUnless(indexLessExists)(CliValidationError(s"index.html doesn't exist in $styles"))
                indexLessIsAFile <- Files[IO].isRegularFile(indexLessFile)
                _ <- IO.raiseUnless(indexLessIsAFile)(CliValidationError(s"$indexLessFile is not a file"))
              yield IndexHtmlConfig.StylesOnly(styles).some).toResource
            case (None, None) =>
              Resource.pure(Option.empty[IndexHtmlConfig])
            case (Some(_), Some(_)) =>
              Resource.raiseError[IO, Nothing, Throwable](
                CliValidationError("path-to-index-html and styles-dir can't be defined at the same time")
              )

          proxyConf2 <- proxyConf(proxyTarget, pathPrefix)
          proxyRoutes: HttpRoutes[IO] = makeProxyRoutes(client, proxyConf2)(logger)

          _ <- buildRunner(
            buildTool,
            linkingTopic,
            baseDirPath,
            outDirPath,
            extraBuildArgs,
            millModuleName
          )(logger)

          app <- routes(outDirString, refreshTopic, indexOpts, proxyRoutes, fileToHashRef, clientRoutingPrefix)(logger)

          _ <- updateMapRef(outDirPath, fileToHashRef)(logger).toResource
          // _ <- stylesDir.fold(Resource.unit)(sd => seedMapOnStart(sd, mr))
          _ <- fileWatcher(outDirPath, fileToHashRef, linkingTopic, refreshTopic)(logger)
          _ <- indexOpts.match
            case Some(IndexHtmlConfig.IndexHtmlPath(indexHtmlatPath)) =>
              staticWatcher(refreshTopic, fs2.io.file.Path(indexHtmlatPath.toString))(logger)
            case _ => Resource.unit[IO]

          // _ <- stylesDir.fold(Resource.unit[IO])(sd => fileWatcher(fs2.io.file.Path(sd), mr))
          _ <- logger.info(s"Start dev server on http://localhost:$port").toResource
          server <- buildServer(app.orNotFound, port)

          - <- openBrowser(Some(openBrowserAt), port)(logger).toResource
        yield server

        server
          .useForever
          .as(ExitCode.Success)
          .handleErrorWith {
            case CliValidationError(message) =>
              IO.println(s"$message\n${command.showHelp}").as(ExitCode.Error)
            case error => IO.raiseError(error)
          }
    }
  end main

  val command =
    val versionFlag = Opts.flag(
      long = "version",
      short = "v",
      help = "Print the version number and exit.",
      visibility = Visibility.Partial
    )

    val version = "0.0.1"
    val finalOpts = versionFlag.as(IO.println(version).as(ExitCode.Success)).orElse(main)
    Command(name = "LiveServer", header = "Scala JS live server", helpFlag = true)(finalOpts)
  end command

  override def run(args: List[String]): IO[ExitCode] =
    CommandIOApp.run(command, args)

  private def toDirectoryPath(path: String) =
    val res = Path(path)
    Files[IO]
      .isDirectory(res)
      .toResource
      .flatMap:
        case true  => Resource.pure(res)
        case false => Resource.raiseError[IO, Nothing, Throwable](CliValidationError(s"$path is not a directory"))
  end toDirectoryPath

end LiveServer
