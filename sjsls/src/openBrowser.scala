package io.github.quafadas.sjsls

import java.net.URI

import com.comcast.ip4s.Port

import scribe.Scribe

import cats.effect.IO

def openBrowser(openBrowserAt: Option[String], port: Port)(logger: Scribe[IO]): IO[Unit] =
  openBrowserAt match
    case None        => logger.trace("No openBrowserAt flag set, so no browser will be opened")
    case Some(value) =>
      val openAt = URI(s"http://localhost:$port$value")
      logger.info(s"Attempting to open browser to $openAt") >>
        platformBrowse(openAt)(logger)
