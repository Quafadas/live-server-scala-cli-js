package io.github.quafadas.RefreshPlugin

import io.github.quafadas.sjsls.LiveServerConfig
import mill.*
import mill.scalalib.*
import mill.scalajslib.*
import os.Path
import mill.api.Task.Simple
import fs2.concurrent.Topic
import cats.effect.IO
// import mill.scalajslib.*
// import coursier.maven.MavenRepository
// import mill.api.Result
// import mill.util.Jvm.createJar
// import mill.define.PathRef
// import mill.scalalib.api.CompilationResult
// // import de.tobiasroeser.mill.vcs.version.VcsVersion
// import scala.util.Try
// import mill.scalalib.publish.PomSettings
// import mill.scalalib.publish.License
// import mill.scalalib.publish.VersionControl
// import os.SubPath
// import ClasspathHelp.*
import cats.effect.unsafe.implicits.global
import io.github.quafadas.sjsls.LiveServerConfig
import cats.effect.ExitCode
import scala.util.{Try, Success, Failure}
import scala.concurrent.Future
import mill.api.BuildCtx
import mill.scalajslib.api.Report
implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

trait ScalaJsRefreshModule extends ScalaJSModule:

  lazy val updateServer = Topic[IO, Unit].unsafeRunSync()


  def indexHtml = Task{
    os.write.over(Task.dest / "index.html", io.github.quafadas.sjsls.vanillaTemplate)
    PathRef(Task.dest / "index.html")
  }

  def assetsDir = Task{
     "assets"
  }

  def assets = Task{
    os.write.over(Task.dest / "style.css", io.github.quafadas.sjsls.lessStyle(true).render)
    PathRef(Task.dest / assetsDir())
  }

  def port = Task {
    8080
  }

  def openBrowser= Task {
    true
  }

  def logLevel = Task {
    "warn"
  }

  def dezombify = Task {
    true
  }

  def siteGen = Task{
    val assets_ = assets()
    val path = fastLinkJS().dest
    os.copy.over(Task.dest / "index.html", indexHtml().path)
    os.copy.over(Task.dest / assetsDir(), assets_.path)
    updateServer.publish1(println("publishing update"))
    (assets_.path.toString(), path.path.toString())
  }

  def lcs = Task.Worker{
    val (assets, js) = siteGen()
    LiveServerConfig(
          baseDir = None,
          outDir = Some(js),
          port = com.comcast.ip4s.Port.fromInt(port()).getOrElse(throw new IllegalArgumentException(s"invalid port: ${port()}")),
          indexHtmlTemplate = Some(assets),
          buildTool = io.github.quafadas.sjsls.NoBuildTool(), // Here we are a slave to the build tool
          openBrowserAt = "/index.html",
          preventBrowserOpen = !openBrowser(),
          dezombify = dezombify(),
          logLevel = logLevel()
        )
  }

  def serve = Task.Worker{
    // Let's kill off anything that is a zombie on the port we want to use
    val p = port()

    BuildCtx.withFilesystemCheckerDisabled {
      new RefreshServer(lcs())
    }
  }

  class RefreshServer(lcs: LiveServerConfig) extends AutoCloseable {
    val server  = io.github.quafadas.sjsls.LiveServer.main(lcs).allocated

    server.map(_._1).unsafeRunSync()

    override def close(): Unit = {
      // This is the shutdown hook for http4s
      println("Shutting down server...")
      server.map(_._2).flatten.unsafeRunSync()
    }
  }

