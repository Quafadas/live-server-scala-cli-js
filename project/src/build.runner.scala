import java.util.Locale

import scala.concurrent.duration.*

import fs2.*
import fs2.concurrent.Topic
import fs2.io.Watcher
import fs2.io.Watcher.Event
import fs2.io.Watcher.Event.Created
import fs2.io.Watcher.Event.Deleted
import fs2.io.Watcher.Event.Modified
import fs2.io.Watcher.Event.NonStandard
import fs2.io.Watcher.Event.Overflow
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes

import scribe.Scribe

import cats.effect.IO
import cats.effect.ResourceIO
import cats.syntax.all.*

sealed trait BuildTool(val invokedVia: String)
class ScalaCli
    extends BuildTool(
      if isWindows then "scala-cli.bat" else "scala-cli"
    )
class Mill
    extends BuildTool(
      if isWindows then "mill.bat" else "mill"
    )

private lazy val isWindows: Boolean =
  System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")

def buildRunner(
    tool: BuildTool,
    linkingTopic: Topic[IO, Unit],
    workDir: fs2.io.file.Path,
    outDir: fs2.io.file.Path,
    extraBuildArgs: List[String],
    millModuleName: Option[String],
    buildToolInvocation: Option[String]
)(
    logger: Scribe[IO]
): ResourceIO[Unit] =
  val invokeVia = buildToolInvocation.getOrElse(tool.invokedVia)
  tool match
    case scli: ScalaCli =>
      buildRunnerScli(linkingTopic, workDir, outDir, invokeVia, extraBuildArgs)(logger)
    case m: Mill =>
      buildRunnerMill(
        linkingTopic,
        workDir,
        millModuleName.getOrElse(throw new Exception("must have a module name when running with mill")),
        invokeVia,
        extraBuildArgs
      )(logger)
  end match
end buildRunner

def buildRunnerScli(
    linkingTopic: Topic[IO, Unit],
    workDir: fs2.io.file.Path,
    outDir: fs2.io.file.Path,
    invokeVia: String,
    extraBuildArgs: List[String]
)(
    logger: Scribe[IO]
): ResourceIO[Unit] =
  val scalaCliArgs = List(
    "--power",
    "package",
    "--js",
    ".",
    "-o",
    outDir.show,
    "-f",
    "-w"
  ) ++ extraBuildArgs

  logger
    .trace(s"Invoking via : $invokeVia with args :  ${scalaCliArgs.toString()}")
    .toResource
    .flatMap(
      _ =>
        ProcessBuilder(
          invokeVia,
          scalaCliArgs
        ).withWorkingDirectory(workDir)
          .spawn[IO]
          .use {
            p =>
              // p.stderr.through(fs2.io.stdout).compile.drain >>
              p.stderr
                .through(text.utf8.decode)
                .debug()
                .chunks
                .evalMap(
                  aChunk =>
                    if aChunk.toString.contains("main.js, run it with") then
                      logger.trace("Detected that linking was successful, emitting refresh event") >>
                        linkingTopic.publish1(())
                    else
                      logger.trace(s"$aChunk :: Linking unfinished") >>
                        IO.unit
                )
                .compile
                .drain
          }
          .background
          .void
    )
end buildRunnerScli

def buildRunnerMill(
    linkingTopic: Topic[IO, Unit],
    workDir: fs2.io.file.Path,
    moduleName: String,
    invokeVia: String,
    extraBuildArgs: List[String]
)(
    logger: Scribe[IO]
): ResourceIO[Unit] =
  // val watchLinkComplePath = workDir / "out" / moduleName / "fastLinkJS.json"

  // val watcher = fs2
  //   .Stream
  //   .resource(Watcher.default[IO].evalTap(_.watch(watchLinkComplePath.toNioPath)))
  //   .flatMap {
  //     _.events(100.millis)
  //       .evalTap {
  //         (e: Event) =>
  //           e match
  //             case Created(path, count) => logger.info("fastLinkJs.json was created")
  //             case Deleted(path, count) => logger.info("fastLinkJs.json was deleted")
  //             case Modified(path, count) =>
  //               logger.info("fastLinkJs.json was modified - link successful => trigger a refresh") >>
  //                 linkingTopic.publish1(())
  //             case Overflow(count)                         => logger.info("overflow")
  //             case NonStandard(event, registeredDirectory) => logger.info("non-standard")

  //       }
  //   }
  //   .compile
  //   .drain
  //   .background
  //   .void

  val millargs = List(
    "-w",
    s"$moduleName.fastLinkJS",
    "-j",
    "0"
  ) ++ extraBuildArgs
  // TODO pipe this to stdout so that we can see linker progress / errors.
  val builder = ProcessBuilder(
    invokeVia,
    millargs
  ).withWorkingDirectory(workDir)
    .spawn[IO]
    .use {
      p =>
        // p.stderr.through(fs2.io.stdout).compile.drain >>
        // val stdOut = p.stdout.through(text.utf8.decode).debug().compile.drain
        // val stdErr = p.stderr.through(text.utf8.decode).debug().compile.drain
        // stdOut.both(stdErr).void

        p.stderr
          .through(text.utf8.decode)
          .debug()
          .chunks
          .evalMap(
            aChunk =>
              if aChunk.head.exists(_.startsWith("Emitter")) then
                logger.trace("Detected that linking was successful, emitting refresh event") >>
                  linkingTopic.publish1(())
              else
                logger.trace(s"$aChunk :: Linking unfinished") >>
                  IO.unit
              end if
          )
          .compile
          .drain
        // .both(stdOut)
        // .both(stdErr).void
    }
    .background
    .void

  for
    _ <- logger.trace("Starting buildRunnerMill").toResource
    _ <- logger.debug(s"running $invokeVia with args $millargs").toResource
    _ <- builder
  // _ <- watcher
  yield ()
  end for

end buildRunnerMill
