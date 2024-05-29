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

sealed trait BuildTool
class ScalaCli extends BuildTool
class Mill extends BuildTool

def buildRunner(
    tool: BuildTool,
    linkingTopic: Topic[IO, Unit],
    workDir: fs2.io.file.Path,
    outDir: fs2.io.file.Path,
    extraBuildArgs: List[String],
    millModuleName: Option[String]
)(
    logger: Scribe[IO]
): ResourceIO[Unit] = tool match
  case scli: ScalaCli => buildRunnerScli(linkingTopic, workDir, outDir, extraBuildArgs)(logger)
  case m: Mill =>
    buildRunnerMill(
      linkingTopic,
      workDir,
      millModuleName.getOrElse(throw new Exception("must have a module name when running with mill")),
      extraBuildArgs
    )(logger)

def buildRunnerScli(
    linkingTopic: Topic[IO, Unit],
    workDir: fs2.io.file.Path,
    outDir: fs2.io.file.Path,
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
    .trace(scalaCliArgs.toString())
    .toResource
    .flatMap(
      _ =>
        ProcessBuilder(
          "scala-cli",
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
    extraBuildArgs: List[String]
)(
    logger: Scribe[IO]
): ResourceIO[Unit] =
  val watchLinkComplePath = workDir / "out" / moduleName / "fastLinkJS.json"

  val watcher = fs2
    .Stream
    .resource(Watcher.default[IO].evalTap(_.watch(watchLinkComplePath.toNioPath)))
    .flatMap {
      _.events(100.millis)
        .evalTap {
          (e: Event) =>
            e match
              case Created(path, count) => logger.info("fastLinkJs.json was created")
              case Deleted(path, count) => logger.info("fastLinkJs.json was deleted")
              case Modified(path, count) =>
                logger.info("fastLinkJs.json was modified - link successful => trigger a refresh") >>
                  linkingTopic.publish1(())
              case Overflow(count)                         => logger.info("overflow")
              case NonStandard(event, registeredDirectory) => logger.info("non-standard")

        }
    }
    .compile
    .drain
    .background
    .void

  // TODO pipe this to stdout so that we can see linker progress / errors.
  val builder = ProcessBuilder(
    "mill",
    List(
      "-w",
      s"$moduleName.fastLinkJS"
    ) ++ extraBuildArgs
  ).withWorkingDirectory(workDir).spawn[IO].useForever.map(_ => ()).background.void

  for
    _ <- logger.trace("Starting buildRunnerMill").toResource
    _ <- logger.trace(s"watching path $watchLinkComplePath").toResource
    _ <- builder
    _ <- watcher
  yield ()
  end for

end buildRunnerMill
