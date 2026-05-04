package io.github.quafadas.sjsls

import java.net.URI

import scribe.Scribe

import cats.effect.IO

private[sjsls] def platformBrowse(uri: URI)(logger: Scribe[IO]): IO[Unit] = IO.unit
