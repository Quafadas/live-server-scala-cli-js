package io.github.quafadas.sjsls

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.DurationInt

import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.dsl.io.*

import fs2.concurrent.Topic

import cats.effect.IO

import _root_.io.circe.syntax.EncoderOps
import cats.effect.kernel.Ref
import scribe.Scribe

def refreshRoutes(
    refreshTopic: Topic[IO, Unit],
    assetRefreshTopic: Topic[IO, String],
    buildTool: BuildTool,
    stringPath: fs2.io.file.Path,
    mr: Ref[IO, Map[String, String]],
    logger: Scribe[IO],
    inMemoryFiles: Option[ConcurrentHashMap[String, Array[Byte]]] = None
) = HttpRoutes.of[IO] {

  val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
  val refresh = refreshTopic
    .subscribe(10)
    .evalTap(_ => logger.debug("[refreshRoute] raw event received from refreshTopic (pre-debounce)"))

  val assetRefresh = assetRefreshTopic
    .subscribe(10)
    .map(AssetRefresh(_))
    .evalTap(s => logger.debug("[assetRefreshRoute] raw event received from assetRefreshTopic (pre-debounce) $s"))


  buildTool match
    case _: NoBuildTool =>

      case GET -> Root / "refresh" / "v1" / "sse" =>
        Ok(
          keepAlive
            .merge(
              refresh
                .evalTap(
                  _ =>
                    // A different tool is responsible for linking, so we hash the files "on the fly" when an update is requested
                    logger.debug("Updating Map Ref") >>
                      (inMemoryFiles match
                        case Some(files) =>
                          logger.debug(
                            s"[refreshRoute] Updating from IN-MEMORY files. count=${files.size()} keys=${scala
                                .jdk
                                .CollectionConverters
                                .SetHasAsScala(files.keySet())
                                .asScala
                                .mkString(", ")}"
                          ) >> updateMapRefFromMemory(files, mr)(logger)
                        case None => updateMapRef(stringPath, mr)(logger))
                )
                .as(PageRefresh())
            )
            .merge(assetRefresh)
            .evalTap(msg => logger.debug(s"Publishing refresh event: $msg"))
            .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
        )
    case _ =>
      case GET -> Root / "refresh" / "v1" / "sse" =>
        Ok(
          keepAlive
            .merge(refresh.as(PageRefresh()))
            .merge(assetRefresh)
            .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
        )
  end match
}
