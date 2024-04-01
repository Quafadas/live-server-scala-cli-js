import cats.effect.IO
import java.security.MessageDigest

val md = MessageDigest.getInstance("MD5")

def fielHash(filePath: fs2.io.file.Path): IO[String] =
  fs2.io.file
    .Files[IO]
    .readUtf8Lines(filePath)
    .compile
    .toList
    .map(lines => md.digest(lines.mkString("\n").getBytes).map("%02x".format(_)).mkString)
