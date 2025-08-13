package io.github.quafadas.sjsls

import scala.concurrent.duration.DurationInt

import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.dsl.io.*

import fs2.concurrent.Topic

import cats.effect.IO

import _root_.io.circe.syntax.EncoderOps

def refreshRoutes(refreshTopic: Topic[IO, Unit]) = HttpRoutes.of[IO] {
  case GET -> Root / "refresh" / "v1" / "sse" =>
    val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
    Ok(
      keepAlive
        .merge(refreshTopic.subscribe(10).as(PageRefresh()))
        .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
    )
}
