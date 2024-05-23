import fs2.*
import fs2.concurrent.Topic
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes

import scribe.Scribe

import cats.effect.IO

def buildRunner(refreshTopic: Topic[IO, String], workDir: fs2.io.file.Path, outDir: fs2.io.file.Path)(
    logger: Scribe[IO]
) =
  ProcessBuilder(
    "scala-cli",
    "--power",
    "package",
    "--js",
    ".",
    "-o",
    outDir.toString(),
    "-f",
    "-w"
  ).withWorkingDirectory(workDir)
    .spawn[IO]
    .use { p =>
      // p.stderr.through(fs2.io.stdout).compile.drain >>
      p.stderr
        .through(text.utf8.decode)
        .debug()
        .chunks
        .evalMap(aChunk =>
          if aChunk.toString.contains("node ./") then
            logger.trace("Detected that linking was successful, emitting refresh event") >>
              refreshTopic.publish1("refresh")
          else
            logger.trace(s"$aChunk :: Linking unfinished") >>
              IO.unit
        )
        .compile
        .drain
    }
    .background
