import cats.effect.IO
import fs2.io.file.*

// TODO: Use last modified time once scala-cli stops
//       copy pasting the files from a temporary directory
//       and performs linking in place
// def fileHash(filePath: Path): IO[String] =
//   Files[IO].getLastModifiedTime(filePath).map(_.toNanos.toString)

def fileHash(filePath: fs2.io.file.Path): IO[String] =
  Files[IO].readAll(filePath).through(fs2.hash.md5).through(fs2.text.hex.encode).compile.string
