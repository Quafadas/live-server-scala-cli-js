// write a basic http4s server

import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.{*, given}

import cats.effect.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import com.comcast.ip4s.host
import com.comcast.ip4s.port
import org.http4s.HttpApp
import org.http4s.server.staticcontent.*
import cats.effect.*

import cats.syntax.all.*

import scala.concurrent.duration.*
import org.http4s.Request
import cats.effect.std.*
import org.http4s.Http
import org.http4s.Response
import cats.data.OptionT
import cats.data.Kleisli
import org.http4s.scalatags.*
import fs2.*
import fs2.io.process.{Processes, ProcessBuilder}
import fs2.io.Watcher
import fs2.io.file.Files
import fs2.io.Watcher.Event
import org.http4s.ServerSentEvent
import _root_.io.circe.Encoder

import cats.syntax.strong
import fs2.concurrent.Topic
import scalatags.Text.styles

sealed trait FrontendEvent derives Encoder.AsObject

case class KeepAlive() extends FrontendEvent derives Encoder.AsObject
case class PageRefresh() extends FrontendEvent derives Encoder.AsObject

object LiveServer extends IOApp:

  private val refreshTopic = Topic[IO, String].toResource

  private val port = port"8085"

  private def buildRunner(refreshTopic: Topic[IO, String], workDir: fs2.io.file.Path, outDir: fs2.io.file.Path) =
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
              // IO.println("emit") >>
              refreshTopic.publish1("hi")
            else IO.unit
          )
          .compile
          .drain
      }
      .background

  private def seedMapOnStart(stringPath: String, mr: MapRef[IO, String, Option[String]]) =
    val asFs2 = fs2.io.file.Path(stringPath)
    fs2.io.file
      .Files[IO]
      .walk(asFs2)
      // .filter(_.endsWith(".js"))
      .evalMap { f =>
        Files[IO]
          .isRegularFile(f)
          .ifM(
            fielHash(f).flatMap(h =>
              val key = asFs2.relativize(f)
              mr.setKeyValue(key.toString(), h)
            ),
            IO.unit
          )
      }
      .compile
      .drain
      .toResource

  end seedMapOnStart

  private def fileWatcher(
      stringPath: fs2.io.file.Path,
      mr: MapRef[IO, String, Option[String]]
  ): ResourceIO[IO[OutcomeIO[Unit]]] =
    fs2.Stream
      .resource(Watcher.default[IO].evalTap(_.watch(stringPath.toNioPath)))
      .flatMap { w =>
        w.events()
          .evalTap((e: Event) =>
            e match
              case Event.Created(path, i) =>
                // if path.endsWith(".js") then
                fielHash(fs2.io.file.Path(path.toString()))
                  .flatMap(h =>
                    val serveAt = path.relativize(stringPath.toNioPath)
                    // IO.println(s"created $path, $h") >>
                    mr.setKeyValue(serveAt.toString(), h)
                  )
              // else IO.unit
              case Event.Modified(path, i) =>
                // if path.endsWith(".js") then
                fielHash(fs2.io.file.Path(path.toString()))
                  .flatMap(h =>
                    val serveAt = path.relativize(stringPath.toNioPath)
                    // IO.println(s"modifed $path , $h") >>
                    mr.setKeyValue(serveAt.toString(), h)
                  )
              // else IO.unit
              case Event.Deleted(path, i) =>
                // if path.endsWith(".js") then
                val serveAt = path.relativize(stringPath.toNioPath)
                // IO.println(s"deleted $path") >>
                mr.unsetKey(serveAt.toString())
              // else IO.unit
              case e: Event.Overflow    => IO.println("overflow")
              case e: Event.NonStandard => IO.println("non-standard")
          )
      }
      .compile
      .drain
      .background
  end fileWatcher

  private def routes(
      stringPath: String,
      refreshTopic: Topic[IO, String],
      stylesPath: String
  ): Resource[IO, (HttpApp[IO], MapRef[IO, String, Option[String]], Ref[IO, Map[String, String]])] =
    Resource.eval(
      for
        ref <- Ref[IO].of(Map.empty[String, String])
        mr = MapRef.fromSingleImmutableMapRef[IO, String, String](ref)
      yield
        val staticFiles = Router(
          "" -> fileService[IO](FileService.Config(stringPath))
        )

        val styles = Router(
          "" -> fileService[IO](FileService.Config(stylesPath))
        )

        val overrides = HttpRoutes
          .of[IO] {
            case GET -> Root =>
              Ok((ref.get.map(_.toSeq.map((path, hash) => (fs2.io.file.Path(path), hash))).map(makeHeader)))
            case GET -> Root / "all" =>
              ref.get.flatMap { m =>
                Ok(m.toString)
              }
            case GET -> Root / "api" / "v1" / "sse" =>
              val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
              Ok(
                keepAlive
                  .merge(refreshTopic.subscribe(10).as(PageRefresh()))
                  .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
              )
          }

        (overrides.combineK(staticFiles).combineK(styles).orNotFound, mr, ref)
    )
  end routes

  private def buildServer(httpApp: HttpApp[IO]) = EmberServerBuilder
    .default[IO]
    .withHttp2
    .withHost(host"localhost")
    .withPort(port)
    .withHttpApp(httpApp)
    .withShutdownTimeout(10.milli)
    .build

  /*
          args(0) is the base directory
          args(1) is the output directory
          args(2) is the styles directory (contains *.less files)
   */
  override def run(args: List[String]): IO[ExitCode] =
    println("args || " + args.mkString(","))
    val baseDir = args.head
    val outDir = args(1)
    val stylesDir = args(2)
    val server = for
      _ <- IO.println(s"Start dev server on https://localhost:$port").toResource
      refreshPub <- refreshTopic
      _ <- buildRunner(refreshPub, fs2.io.file.Path(baseDir), fs2.io.file.Path(outDir))
      routes <- routes(outDir.toString(), refreshPub, stylesDir)
      (app, mr, ref) = routes
      _ <- seedMapOnStart(outDir, mr)
      _ <- seedMapOnStart(stylesDir, mr)
      _ <- fileWatcher(fs2.io.file.Path(outDir), mr)
      _ <- fileWatcher(fs2.io.file.Path(stylesDir), mr)
      server <- buildServer(app)
    yield server

    server.use(_ => IO.never).as(ExitCode.Success)

  end run
end LiveServer

val s: String = 1
