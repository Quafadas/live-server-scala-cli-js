package io.github.quafadas.sjsls

import java.awt.Desktop
import java.net.URI

import scribe.Scribe

import cats.effect.IO

private[sjsls] def platformBrowse(uri: URI)(logger: Scribe[IO]): IO[Unit] =
  IO(
    if Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) then
      IO(Desktop.getDesktop().browse(uri))
    else logger.error("Desktop not supported, so can't open browser")
  ).flatten
