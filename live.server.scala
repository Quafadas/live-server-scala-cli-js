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

sealed trait FrontendEvent(val typ: String) derives Encoder.AsObject
case class KeepAlive(override val typ: String = "keepAlive") extends FrontendEvent(typ) derives Encoder.AsObject
case class PageRefresh(override val typ: String = "pageRefresh") extends FrontendEvent(typ) derives Encoder.AsObject

object LiveServer extends IOApp:

  private val refreshTopic = Topic[IO, String].toResource

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
      .filter(_.endsWith(".js"))
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
                if path.endsWith(".js") then
                  fielHash(fs2.io.file.Path(path.toString()))
                    .flatMap(h =>
                      val serveAt = path.relativize(stringPath.toNioPath)
                      // IO.println(s"created $path, $h") >>
                      mr.setKeyValue(serveAt.toString(), h)
                    )
                else IO.unit
              case Event.Modified(path, i) =>
                if path.endsWith(".js") then
                  fielHash(fs2.io.file.Path(path.toString()))
                    .flatMap(h =>
                      val serveAt = path.relativize(stringPath.toNioPath)
                      // IO.println(s"modifed $path , $h") >>
                      mr.setKeyValue(serveAt.toString(), h)
                    )
                else IO.unit
              case Event.Deleted(path, i) =>
                if path.endsWith(".js") then
                  val serveAt = path.relativize(stringPath.toNioPath)
                  // IO.println(s"deleted $path") >>
                  mr.unsetKey(serveAt.toString())
                else IO.unit
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
      refreshTopic: Topic[IO, String]
  ): Resource[IO, (HttpApp[IO], MapRef[IO, String, Option[String]], Ref[IO, Map[String, String]])] =
    Resource.eval(
      for
        ref <- Ref[IO].of(Map.empty[String, String])
        mr = MapRef.fromSingleImmutableMapRef[IO, String, String](ref)
      yield
        val staticFiles = Router(
          "" -> fileService[IO](FileService.Config(stringPath))
        )

        val overrides = HttpRoutes
          .of[IO] {
            case GET -> Root / "index.html" =>
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

        (overrides.combineK(staticFiles).orNotFound, mr, ref)
    )
  end routes

  private def buildServer(httpApp: HttpApp[IO]) = EmberServerBuilder
    .default[IO]
    .withHttp2
    .withHost(host"localhost")
    .withPort(port"8085")
    .withHttpApp(httpApp)
    .withShutdownTimeout(10.milli)
    .build

  /*
          args.head is the base directory
          args.tail.head is the output directory
   */
  override def run(args: List[String]): IO[ExitCode] =
    println("args || " + args.mkString(","))
    val baseDir = args.head
    val outDir = args.tail.head
    val server = for
      _ <- IO.println("Start dev server ").toResource
      refreshPub <- refreshTopic
      _ <- buildRunner(refreshPub, fs2.io.file.Path(baseDir), fs2.io.file.Path(outDir))
      routes <- routes(outDir.toString(), refreshPub)
      (app, mr, ref) = routes
      _ <- seedMapOnStart(args.head, mr)
      _ <- fileWatcher(fs2.io.file.Path(baseDir), mr)
      server <- buildServer(app)
    yield server

    server.use(_ => IO.never).as(ExitCode.Success)

  end run
end LiveServer
