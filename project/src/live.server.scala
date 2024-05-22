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
import com.comcast.ip4s.Port
import org.http4s.HttpApp
import org.http4s.server.staticcontent.*
import cats.effect.*

import cats.syntax.all.*

import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

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
import cats.implicits.*
import com.monovore.decline.*
import com.monovore.decline.effect.*

sealed trait FrontendEvent derives Encoder.AsObject

case class KeepAlive() extends FrontendEvent derives Encoder.AsObject
case class PageRefresh() extends FrontendEvent derives Encoder.AsObject

def makeProxyConfig(frontendPort: Port, proxyTo: Port, matcher: String) = s"""
http:
  servers:
    - listen: $frontendPort
      serverNames:
        - localhost
      locations:
        - matcher: $matcher
          proxyPass: http://$$backend

  upstreams:
    - name: backend
      servers:
        - host: localhost
          port: $proxyTo
          weight: 5
"""

object LiveServer
    extends CommandIOApp(
      name = "LiveServer",
      header = "Scala JS live server",
      version = "0.0.1"
    ):

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

  def routes(
      stringPath: String,
      refreshTopic: Topic[IO, String],
      stylesPath: String,
      proxyRoutes: HttpRoutes[IO]
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

        (overrides.combineK(staticFiles).combineK(styles).combineK(proxyRoutes).orNotFound, mr, ref)
    )
  end routes

  private def buildServer(httpApp: HttpApp[IO], port: Port) = EmberServerBuilder
    .default[IO]
    .withHttp2
    .withHost(host"localhost")
    .withPort(port)
    .withHttpApp(httpApp)
    .withShutdownTimeout(10.milli)
    .build

  // override def main: Opts[IO[ExitCode]] =
  //   (showProcessesOpts orElse buildOpts).map {
  //     case ShowProcesses(all)           => ???
  //     case BuildImage(dockerFile, path) => ???
  //   }

  val baseDirOpt =
    Opts
      .option[String]("project-dir", "The fully qualified location of your project - e.g. c:/temp/helloScalaJS")
      .withDefault(os.pwd.toString())
      .validate("Must be a directory")(s => os.isDir(os.Path(s)))

  val outDirOpt = Opts
    .option[String]("out-dir", "Where the compiled JS will end up - e.g. c:/temp/helloScalaJS/.out")
    .withDefault((os.pwd / ".out").toString())
    .validate("Must be a directory")(s => os.isDir(os.Path(s)))

  val stylesDirOpt = Opts
    .option[String](
      "styles-dir",
      "A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles"
    )
    .withDefault((os.pwd / "styles").toString())
    .validate("Must be a directory")(s => os.isDir(os.Path(s)))

  val portOpt = Opts
    .option[Int]("port", "The port yo want to run the server on - e.g. 8085")
    .withDefault(3000)
    .validate("Port must be between 1 and 65535")(i => i > 0 && i < 65535)
    .map(i => Port.fromInt(i).get)

  override def main: Opts[IO[ExitCode]] =

    given R: Random[IO] = Random.javaUtilConcurrentThreadLocalRandom[IO]

    (baseDirOpt, outDirOpt, stylesDirOpt, portOpt).mapN { (baseDir, outDir, stylesDir, port) =>

      val proxyConfig = ProxyConfig.loadYaml[IO](makeProxyConfig(port"3000", port"8080", "/api")).toResource

      val server = for
        client <- EmberClientBuilder.default[IO].build
        pc <- proxyConfig
        proxyRoutes: HttpRoutes[IO] = HttpProxy.servers(pc, client).head._2

        _ <- IO.println(s"Start dev server on https://localhost:$port").toResource

        refreshPub <- refreshTopic
        _ <- buildRunner(refreshPub, fs2.io.file.Path(baseDir), fs2.io.file.Path(outDir))
        routes <- routes(outDir.toString(), refreshPub, stylesDir, proxyRoutes)
        (app, mr, ref) = routes
        _ <- seedMapOnStart(outDir, mr)
        _ <- seedMapOnStart(stylesDir, mr)
        _ <- fileWatcher(fs2.io.file.Path(outDir), mr)
        _ <- fileWatcher(fs2.io.file.Path(stylesDir), mr)
        server <- buildServer(app, port)
      yield server

      server.use(_ => IO.never).as(ExitCode.Success)
    }
  end main
end LiveServer
