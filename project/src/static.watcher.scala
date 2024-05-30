import java.time.format.DateTimeFormatter

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
import fs2.io.file.Path

import scribe.Scribe

import cats.effect.*
import cats.effect.IO
import cats.effect.OutcomeIO
import cats.effect.ResourceIO
import cats.syntax.all.*
import fs2.io.file.Files
import java.time.ZonedDateTime

def staticWatcher(
    refreshTopic: Topic[IO, Unit],
    staticDir: fs2.io.file.Path
    // mr: MapRef[IO, String, Option[String]]
)(
    logger: Scribe[IO]
): ResourceIO[IO[OutcomeIO[Unit]]] =
  val nioPath = staticDir.toNioPath

  def refreshAsset(path: java.nio.file.Path, op: String): IO[Unit] =
    for
      // lastModified <- fileLastModified(path)
      _ <- logger.trace(s"$path was $op ")
      // serveAt = path.relativize(nioPath)
      // _ <- mr.setKeyValue(serveAt.toString(), lastModified)
      _ <- fs2
        .io
        .file
        .Files[IO]
        .isRegularFile(Path(path.toString()))
        .map(b => b && !path.toString().endsWith(".less")) // don't force a refrseh if we're editing a .less file
        .ifM(
          refreshTopic.publish1(()),
          IO.unit
        )
    yield ()

  fs2
    .Stream
    .resource(Watcher.default[IO].evalTap(_.watch(nioPath)))
    .flatMap {
      _.events(500.millis)
        .evalTap {
          (e: Event) =>
            e match
              case Created(path, count) =>
                refreshAsset(path, "modified")
              case Deleted(path, count) =>
                logger.trace(s"$path was deleted, not requesting refresh")
              case Modified(path, count) =>
                refreshAsset(path, "modified")
              case Overflow(count)                         => logger.trace("overflow")
              case NonStandard(event, registeredDirectory) => logger.trace("non-standard")

        }
    }
    .compile
    .drain
    .background

end staticWatcher

val formatter = DateTimeFormatter.RFC_1123_DATE_TIME

def httpCacheFormat(zdt: ZonedDateTime): String =
  formatter.format(zdt)

def updateMapRef(stringPath: fs2.io.file.Path, mr: Ref[IO, Map[String, String]])(logger: Scribe[IO]) =
  Files[IO]
    .walk(stringPath)
    .evalFilter(Files[IO].isRegularFile)
    .parEvalMap(maxConcurrent = 8)(path => fileHash(path).map(path -> _))
    .compile
    .toVector
    .flatMap(
      vector =>
        val newMap = vector
          .view
          .map(
            (path, hash) =>
              val relativizedPath = stringPath.relativize(path).toString
              relativizedPath -> hash
          )
          .toMap
        logger.trace(s"Updated hashes $newMap") *> mr.set(newMap)
    )

private def fileWatcher(
    stringPath: fs2.io.file.Path,
    mr: Ref[IO, Map[String, String]],
    linkingTopic: Topic[IO, Unit],
    refreshTopic: Topic[IO, Unit]
)(logger: Scribe[IO]): ResourceIO[Unit] =
  linkingTopic
    .subscribe(10)
    .evalTap {
      _ =>
        updateMapRef(stringPath, mr)(logger) >> refreshTopic.publish1(())
    }
    .compile
    .drain
    .background
    .void
end fileWatcher
