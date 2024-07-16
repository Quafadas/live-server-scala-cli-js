package io.github.quafadas.sjsls

import fs2.io.file.*

import cats.effect.IO

// TODO: Use last modified time once scala-cli stops
//       copy pasting the files from a temporary directory
//       and performs linking in place
def fileLastModified(filePath: fs2.io.file.Path): IO[Long] =
  fs2.io.file.Files[IO].getLastModifiedTime(filePath).map(_.toSeconds)

// def fileLastModified(filePath: Path): IO[FiniteDuration] =
//   fs2.io.file.Files[IO].getLastModifiedTime(fs2.io.file.Path(filePath.toString()))

def fileHash(filePath: fs2.io.file.Path): IO[String] =
  fs2.io.file.Files[IO].readAll(filePath).through(fs2.hash.md5).through(fs2.text.hex.encode).compile.string
