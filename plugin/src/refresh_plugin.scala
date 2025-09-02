package io.github.quafadas

import io.github.quafadas.sjsls.LiveServerConfig
import mill.*
import mill.scalalib.*
import mill.scalajslib.*

import mill.api.Task.Simple
import fs2.concurrent.Topic
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.github.quafadas.sjsls.LiveServerConfig
import mill.api.BuildCtx
import mill.scalajslib.api.Report
implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

trait ScalaJsRefreshModule extends ScalaJSModule:

  lazy val updateServer = Topic[IO, Unit].unsafeRunSync()


  def indexHtml = Task{
    os.write.over(Task.dest / "index.html", io.github.quafadas.sjsls.vanillaTemplate(withStyles()))
    PathRef(Task.dest / "index.html")
  }

  def assetsDir =
     super.moduleDir / "assets"

  def withStyles = Task{ true}

  def assets = Task.Source{
    assetsDir
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
      val path = fastLinkJS().dest.path
      os.copy.over(indexHtml().path, Task.dest / "index.html")
      os.copy(assets_.path, Task.dest, mergeFolders = true)
      updateServer.publish1(println("publish update")).unsafeRunSync()
      (Task.dest.toString(), assets_.path.toString(), path.toString())

  }

  def lcs = Task.Worker{
    val (site, assets, js) = siteGen()
    println("Gen lsc")
    LiveServerConfig(
          baseDir = None,
          outDir = Some(js),
          port = com.comcast.ip4s.Port.fromInt(port()).getOrElse(throw new IllegalArgumentException(s"invalid port: ${port()}")),
          indexHtmlTemplate = Some(site),
          buildTool = io.github.quafadas.sjsls.NoBuildTool(), // Here we are a slave to the build tool
          openBrowserAt = "/index.html",
          preventBrowserOpen = !openBrowser(),
          dezombify = dezombify(),
          logLevel = logLevel(),
          customRefresh = Some(updateServer)
        )
  }

  def serve = Task.Worker{

    println(lcs())
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

