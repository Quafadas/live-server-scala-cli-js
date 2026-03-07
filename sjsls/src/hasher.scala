package io.github.quafadas.sjsls

import fs2.io.file.*

import cats.effect.IO

// TODO: Use last modified time once scala-cli stops
//       copy pasting the files from a temporary directory
//       and performs linking in place
inline def fileLastModified(filePath: fs2.io.file.Path): IO[Long] =
  fs2.io.file.Files[IO].getLastModifiedTime(filePath).map(_.toSeconds)

// def fileLastModified(filePath: Path): IO[FiniteDuration] =
//   fs2.io.file.Files[IO].getLastModifiedTime(fs2.io.file.Path(filePath.toString()))

inline def uriIsHashed(uri: org.http4s.Uri.Path): Boolean =
  uri.toString.matches(".*\\.[a-f0-9]{8,}\\..*")

inline def fileAlreadyHashed(filePath: fs2.io.file.Path): Boolean =
  filePath.fileName.toString.matches(".*\\.[a-f0-9]{8,}\\..*")

inline def fileHash(filePath: fs2.io.file.Path): IO[String] =
  // If the filename itself, already contains a hash, then let's accept it as is and not waste time hashing the file again
  if fileAlreadyHashed(filePath) then IO.pure(filePath.fileName.toString)
  else fs2.io.file.Files[IO].readAll(filePath).through(fs2.hash.md5).through(fs2.text.hex.encode).compile.string
