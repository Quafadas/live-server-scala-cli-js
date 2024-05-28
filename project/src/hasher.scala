import cats.effect.IO
import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

// TODO: Use last modified time once scala-cli stops
//       copy pasting the files from a temporary directory
//       and performs linking in place
def fileLastModified(filePath: fs2.io.file.Path): IO[FiniteDuration] =
  fs2.io.file.Files[IO].getLastModifiedTime(filePath)

def fileLastModified(filePath: Path): IO[FiniteDuration] =
  fs2.io.file.Files[IO].getLastModifiedTime(fs2.io.file.Path(filePath.toString()))

def fileHash(filePath: fs2.io.file.Path): IO[String] =
  fs2.io.file.Files[IO].readAll(filePath).through(fs2.hash.md5).through(fs2.text.hex.encode).compile.string
