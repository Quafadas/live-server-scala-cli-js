package io.github.quafadas.sjsls

import scala.concurrent.duration.DurationInt

import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.dsl.io.*

import fs2.concurrent.Topic

import cats.effect.IO

import _root_.io.circe.syntax.EncoderOps
import cats.effect.kernel.Ref
import scribe.Scribe
import cats.syntax.all.*


def refreshRoutes(refreshTopic: Topic[IO, Unit], buildTool: BuildTool,stringPath: fs2.io.file.Path, mr: Ref[IO, Map[String, String]], logger: Scribe[IO]) = HttpRoutes.of[IO] {

  val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
  val refresh = refreshTopic
              .subscribe(10)

  buildTool match
    case _: NoBuildTool =>
      case GET -> Root / "refresh" / "v1" / "sse" =>
        Ok(
          keepAlive
            .merge(
              refresh
              .evalTap(_ =>
                // A different tool is responsible for linking, so we hash the files "on the fly" when an update is requested
                logger.debug("Updating Map Ref") >>
                updateMapRef(stringPath, mr)(logger)
              )
              .as(PageRefresh())
            )
            .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
        )
    case _ =>
      case GET -> Root / "refresh" / "v1" / "sse" =>
        println("Hit this one")
        Ok(
          keepAlive
            .merge(refresh.as(PageRefresh()))
            .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
        )
}
