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
    .withShutdownTimeout(Duration.Zero)
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
    logFileOpt,
    None.pure[Opts],
    None.pure[Opts],
    (workspaceRootOpt, workspaceUuidOpt).mapN {
      case (Some(root), uuidOpt) =>
        val uuid = uuidOpt.getOrElse(java.util.UUID.randomUUID().toString)
        Some((root, uuid))
      case _ => None
    },
    None.pure[Opts]
  ).mapN(LiveServerConfig.apply)

  def main(lsc: LiveServerConfig): Resource[IO, Server] =

    val level = Level.get(lsc.logLevel).get
    val baseLogger = scribe.Logger.root.clearHandlers().clearModifiers()
    lsc
      .logFile
      .fold(baseLogger.withHandler(minimumLevel = Some(level))) {
        filePath =>
          baseLogger.withHandler(
            writer = fileLogWriter(filePath),
            minimumLevel = Some(level)
          )
      }
      .replace()

    val server = for
      _ <- logger
        .debug(
          lsc.toString()
        )
        .toResource

      _ <- Resource
        .pure[IO, Boolean](lsc.dezombify)
        .flatMap(
          if _ then
            Resource.eval(IO.println(s"Attempt to kill off process on port ${lsc.port}")) >>
              dezombify(lsc.port)
          else scribe.cats[IO].debug(s"Assuming port ${lsc.port} is free").toResource
        )
      fileToHashRef <- Ref[IO].of(Map.empty[String, String]).toResource
      refreshTopic <- lsc
        .customRefresh
        .fold(Topic[IO, Unit])(
          t =>
            scribe.cats[IO].debug("Custom refresh topic supplied — wrapping with debug tap") >>
              IO(t)
        )
        .toResource
      // Tap the refreshTopic so every publication is visible in the log regardless of source.
      _ <- refreshTopic
        .subscribe(Int.MaxValue)
        .evalTap(_ => scribe.cats[IO].debug("[refreshTopic] event published (source: any publisher)"))
        .compile
        .drain
        .background
        .void
      assetRefreshTopic <- lsc
        .customAssetRefresh
        .fold(Topic[IO, String])(
          t =>
            scribe.cats[IO].debug("Custom asset refresh topic supplied") >>
              IO(t)
        )
        .toResource
      linkingTopic <- Topic[IO, Unit].toResource
      client <- EmberClientBuilder.default[IO].build
      baseDirPath <- lsc.baseDir.fold(Files[IO].currentWorkingDirectory.toResource)(toDirectoryPath)
      outDirPath <- lsc
        .outDir
        .fold {
          // If we arent' responsible for linking and the user has specified a location for index html, we're essentially serving a static site.
          (lsc.buildTool, lsc.indexHtmlTemplate) match
            case (_: NoBuildTool, Some(indexHtml)) => toDirectoryPath(indexHtml)
            case _                                 => Files[IO].tempDirectory
        }(toDirectoryPath)
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

      // For ScalaCli mode, build an in-memory content-hashed file map so that
      // internal JS modules are served with immutable cache headers and cache
      // busting happens automatically on each rebuild.  Files on disk are never
      // modified.
      cliInMemoryFiles <- (lsc.buildTool, lsc.inMemoryFiles) match
        case (_: ScalaCli, None) =>
          logger.debug("[liveServer] ScalaCli mode — creating CLI in-memory content-hash map").toResource >>
            ContentHasher.buildInMemoryHashedFiles(outDirPath)(logger).map(f => Some(f)).toResource
        case _ =>
          Resource.pure[IO, Option[java.util.concurrent.ConcurrentHashMap[String, Array[Byte]]]](None)

      // Plugin-provided inMemoryFiles take priority; CLI-created is the fallback.
      effectiveInMemoryFiles = lsc.inMemoryFiles.orElse(cliInMemoryFiles)

      app <- routes(
        outDirString,
        refreshTopic,
        assetRefreshTopic,
        indexOpts,
        proxyRoutes,
        fileToHashRef,
        lsc.clientRoutingPrefix,
        lsc.injectPreloads,
        lsc.buildTool,
        lsc.devToolsWorkspace,
        effectiveInMemoryFiles
      )(logger)

      _ <- effectiveInMemoryFiles match
        case Some(files) =>
          logger
            .debug(
              s"[liveServer] Seeding hash ref from IN-MEMORY files. count=${files
                  .size()} keys=${scala.jdk.CollectionConverters.SetHasAsScala(files.keySet()).asScala.mkString(", ")}"
            )
            .toResource >>
            updateMapRefFromMemory(files, fileToHashRef)(logger).toResource
        case None =>
          logger.debug(s"[liveServer] Seeding hash ref from DISK. outDirPath=$outDirPath").toResource >>
            updateMapRef(outDirPath, fileToHashRef)(logger).toResource
      // _ <- stylesDir.fold(Resource.unit)(sd => seedMapOnStart(sd, mr))
      _ <- fileWatcher(outDirPath, fileToHashRef, linkingTopic, refreshTopic, cliInMemoryFiles)(logger)
      // Only watch the indexHtmlTemplate dir for changes when the caller has NOT supplied a
      // customRefresh topic.  When customRefresh is present (e.g. the Mill plugin) the caller
      // already publishes explicitly at the end of each build step; the staticWatcher watching
      // the same output directory would fire a second time for the same logical change.
      _ <- (lsc.customRefresh, indexOpts) match
        case (None, Some(IndexHtmlConfig.IndexHtmlPath(indexHtmlatPath))) =>
          staticWatcher(refreshTopic, assetRefreshTopic, fs2.io.file.Path(indexHtmlatPath.toString))(logger)
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

/** A scribe [[scribe.writer.Writer]] that appends to a plain file using Java IO. This deliberately avoids the
  * `scribe-file` module to sidestep the `path""` string-context conflict with http4s.
  */
private[sjsls] def fileLogWriter(filePath: String): scribe.writer.Writer =
  import java.io.{FileOutputStream, PrintStream}
  val ps = new PrintStream(new FileOutputStream(filePath, /*append=*/ true), /*autoFlush=*/ true)
  new scribe.writer.Writer:
    override def write(
        record: scribe.LogRecord,
        output: scribe.output.LogOutput,
        outputFormat: scribe.output.format.OutputFormat
    ): Unit = ps.println(output.plainText)
  end new
end fileLogWriter
