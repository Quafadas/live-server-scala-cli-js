package io.github.quafadas.sjsls

import scala.concurrent.duration.*
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

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

object LiveServer extends IOApp:
  private val logger = scribe.cats[IO]
  given filesInstance: Files[IO] = Files.forAsync[IO]

  import CliOps.*

  private def buildServer(httpApp: HttpApp[IO], port: Port) = EmberServerBuilder
    .default[IO]
    .withHttp2
    .withHost(host"localhost")
    .withPort(port)
    .withHttpApp(httpApp)
    .withShutdownTimeout(1.milli)
    .build


  def parseOpts = (
    baseDirOpt,
    outDirOpt,
    portOpt,
    proxyPortTargetOpt,
    proxyPathMatchPrefixOpt,
    clientRoutingPrefixOpt,
    logLevelOpt,
    buildToolOpt,
    openBrowserAtOpt,
    preventBrowserOpenOpt,
    extraBuildArgsOpt,
    millModuleNameOpt,
    stylesDirOpt,
    indexHtmlTemplateOpt,
    buildToolInvocation,
    injectPreloadsOpt,
    dezombifyOpt,
    None.pure[Opts]
  ).mapN(LiveServerConfig.apply)

  def main(lsc: LiveServerConfig): Resource[IO, Server] =

    scribe
      .Logger
      .root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(Level.get(lsc.logLevel).get))
      .replace()

    val server = for
      _ <- logger
        .debug(
          lsc.toString()
        )
        .toResource

      _ <- Resource.pure[IO, Boolean](lsc.dezombify).flatMap(
        if(_)
          Resource.eval(IO.println(s"Attempt to kill off process on port ${lsc.port}")) >>
          dezombify(lsc.port)
        else
          scribe.cats[IO].debug(s"Assuming port ${lsc.port} is free").toResource
      )
      fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
      refreshTopic <- lsc.customRefresh.fold(Topic[IO, Unit])(IO(_)).toResource
      linkingTopic <- Topic[IO, Unit].toResource
      client <- EmberClientBuilder.default[IO].build
      baseDirPath <- lsc.baseDir.fold(Files[IO].currentWorkingDirectory.toResource)(toDirectoryPath)
      outDirPath <- lsc.outDir.fold(Files[IO].tempDirectory)(toDirectoryPath)
      outDirString = outDirPath.show
      indexHtmlTemplatePath <- lsc.indexHtmlTemplate.traverse(toDirectoryPath)
      stylesDirPath <- lsc.stylesDir.traverse(toDirectoryPath)

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
            _ <- IO.raiseUnless(indexLessExists)(CliValidationError(s"index.less doesn't exist in $styles"))
            indexLessIsAFile <- Files[IO].isRegularFile(indexLessFile)
            _ <- IO.raiseUnless(indexLessIsAFile)(CliValidationError(s"$indexLessFile is not a file"))
          yield IndexHtmlConfig.StylesOnly(styles).some).toResource
        case (None, None) =>
          Resource.pure(Option.empty[IndexHtmlConfig])
        case (Some(_), Some(_)) =>
          Resource.raiseError[IO, Nothing, Throwable](
            CliValidationError("path-to-index-html and styles-dir can't be defined at the same time")
          )

      proxyConf2 <- proxyConf(lsc.proxyPortTarget, lsc.proxyPathMatchPrefix)
      proxyRoutes: HttpRoutes[IO] = makeProxyRoutes(client, proxyConf2)(logger)

      _ <- buildRunner(
        lsc.buildTool,
        linkingTopic,
        baseDirPath,
        outDirPath,
        lsc.extraBuildArgs,
        lsc.millModuleName,
        lsc.buildToolInvocation
      )(logger)

      app <- routes(
        outDirString,
        refreshTopic,
        indexOpts,
        proxyRoutes,
        fileToHashRef,
        lsc.clientRoutingPrefix,
        lsc.injectPreloads
      )(logger)

      _ <- updateMapRef(outDirPath, fileToHashRef)(logger).toResource
      // _ <- stylesDir.fold(Resource.unit)(sd => seedMapOnStart(sd, mr))
      _ <- fileWatcher(outDirPath, fileToHashRef, linkingTopic, refreshTopic)(logger)
      _ <- indexOpts.match
        case Some(IndexHtmlConfig.IndexHtmlPath(indexHtmlatPath)) =>
          staticWatcher(refreshTopic, fs2.io.file.Path(indexHtmlatPath.toString))(logger)
        case _ => Resource.unit[IO]

      // _ <- stylesDir.fold(Resource.unit[IO])(sd => fileWatcher(fs2.io.file.Path(sd), mr))
      _ <- logger.info(s"Start dev server on http://localhost:${lsc.port}").toResource
      server <- buildServer(app.orNotFound, lsc.port)

      _ <- IO.whenA(!lsc.preventBrowserOpen)(openBrowser(Some(lsc.openBrowserAt), lsc.port)(logger)).toResource
    yield server

    server
    // .useForever
    // .as(ExitCode.Success)
    // .handleErrorWith {
    //   case CliValidationError(message) =>
    //     IO.println(s"$message\n${command.showHelp}").as(ExitCode.Error)
    //   case error => IO.raiseError(error)
    // }

  end main

  def runServerHandleErrors(lsc: LiveServerConfig): IO[ExitCode] =
    main(lsc).useForever.as(ExitCode.Success)

  def runServerHandleErrors: Opts[IO[ExitCode]] = parseOpts.map(runServerHandleErrors(_).handleErrorWith {
    case CliValidationError(message) =>
      IO.println(s"${command.showHelp} \n $message \n see help above").as(ExitCode.Error)
    case error => IO.raiseError(error)
  })

  val command =
    val versionFlag = Opts.flag(
      long = "version",
      short = "v",
      help = "Print the version number and exit.",
      visibility = Visibility.Partial
    )

    val version = "0.0.1"
    val finalOpts = versionFlag
      .as(IO.println(version).as(ExitCode.Success))
      .orElse(
        runServerHandleErrors
      )
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
