import fs2.*
import fs2.concurrent.Topic
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes
import fs2.io.Watcher
import fs2.io.Watcher.Event
import fs2.io.file.Files

import scribe.Scribe

import cats.effect.IO
import cats.effect.OutcomeIO
import cats.effect.ResourceIO
import fs2.io.Watcher.Event.Created
import fs2.io.Watcher.Event.Deleted
import fs2.io.Watcher.Event.Modified
import fs2.io.Watcher.Event.Overflow
import fs2.io.Watcher.Event.NonStandard

sealed trait BuildTool
class ScalaCli extends BuildTool
class Mill extends BuildTool

def buildRunner(
    tool: BuildTool,
    refreshTopic: Topic[IO, String],
    workDir: fs2.io.file.Path,
    outDir: fs2.io.file.Path,
    extraBuildArgs: List[String],
    millModuleName: Option[String]
)(
    logger: Scribe[IO]
): ResourceIO[IO[OutcomeIO[Unit]]] = tool match
  case scli: ScalaCli => buildRunnerScli(refreshTopic, workDir, outDir, extraBuildArgs)(logger)
  case m: Mill =>
    buildRunnerMill(
      refreshTopic,
      workDir,
      millModuleName.getOrElse(throw new Exception("must have a moduile name when running with mill")),
      extraBuildArgs
    )(logger)

def buildRunnerScli(
    refreshTopic: Topic[IO, String],
    workDir: fs2.io.file.Path,
    outDir: fs2.io.file.Path,
    extraBuildArgs: List[String]
)(
    logger: Scribe[IO]
): ResourceIO[IO[OutcomeIO[Unit]]] =
  ProcessBuilder(
    "scala-cli",
    List(
      "--power",
      "package",
      "--js",
      ".",
      "-o",
      outDir.toString(),
      "-f",
      "-w"
    ) ++ extraBuildArgs
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
              if aChunk.toString.contains("node ./") then
                logger.trace("Detected that linking was successful, emitting refresh event") >>
                  refreshTopic.publish1("refresh")
              else
                logger.trace(s"$aChunk :: Linking unfinished") >>
                  IO.unit
          )
          .compile
          .drain
    }
    .background
end buildRunnerScli

def buildRunnerMill(
    refreshTopic: Topic[IO, String],
    workDir: fs2.io.file.Path,
    moduleName: String,
    extraBuildArgs: List[String]
)(
    logger: Scribe[IO]
): ResourceIO[IO[OutcomeIO[Unit]]] =
  val watchLinkComplePath = workDir / "out" / moduleName / "fastLinkJS.json"

  val watcher = fs2
    .Stream
    .resource(Watcher.default[IO].evalTap(_.watch(watchLinkComplePath.toNioPath)))
    .flatMap {
      _.events()
        .evalTap {
          (e: Event) =>
            e match
              case Created(path, count) => logger.info("fastLinkJs.json was created")
              case Deleted(path, count) => logger.info("fastLinkJs.json was deleted")
              case Modified(path, count) =>
                logger.info("fastLinkJs.json was modified - link successful => trigger a refresh") >>
                  refreshTopic.publish1("refresh")
              case Overflow(count)                         => logger.info("overflow")
              case NonStandard(event, registeredDirectory) => logger.info("non-standard")

        }
    }
    .compile
    .drain
    .background

  // TODO pipe this to stdout so that we can see linker progress / errors.
  val builder = ProcessBuilder(
    "mill",
    List(
      "-w",
      s"$moduleName.fastLinkJS"
    ) ++ extraBuildArgs
  ).withWorkingDirectory(workDir).spawn[IO].useForever.map(_ => ()).background

  for
    _ <- logger.trace("Starting buildRunnerMill").toResource
    _ <- logger.trace(s"watching path $watchLinkComplePath").toResource
    builder <- builder
    watcher <- watcher
  yield builder >> watcher
  end for

end buildRunnerMill
