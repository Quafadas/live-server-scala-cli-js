package io.github.quafadas
import fs2.concurrent.Topic

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scalatags.Text.all.*

import io.github.quafadas.sjsls.LiveServerConfig
import mill.Task
import mill.PathRef
import mill.api.BuildCtx
import mill.api.Task.Simple
import mill.scalajslib.*
import mill.scalajslib.config.ScalaJSConfigModule
import mill.scalajslib.api.Report
import mill.scalajslib.api.ModuleKind
implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

import java.net.InetSocketAddress
import java.nio.file.Path
import com.sun.net.httpserver.SimpleFileServer

trait ScalaJsWebAppModule extends ScalaJsRefreshModule with FileBasedContentHashScalaJSModule

trait ScalaJsRefreshModule extends ScalaJSConfigModule:

  lazy val updateServer = Topic[IO, Unit].unsafeRunSync()

  override def moduleKind: Simple[ModuleKind] = ModuleKind.ESModule

  def indexHtmlHead = Task {
    head(
      meta(charset := "utf-8"),
      meta(name := "viewport", content := "width=device-width, initial-scale=1"),
      title := titleString,
      externalStylesheets.map { hrefLink =>
        link(href := hrefLink, rel := "stylesheet")
      },
      if hasLess then script(src := "https://cdn.jsdelivr.net/npm/less@4.6.3/dist/less.min.js") else frag(),
      if hasLess then link(rel := "stylesheet/less", href := "/index.less", `type` := "text/css") else frag(),
      if stylesAutoRefresh() && hasLess then script("less.watch();") else frag()
    ).render
  }

  def externalStylesheets = Seq.empty[String]

  def appRoot: String = "app"

  def titleString: String = "App"

  def indexHtmlBody = Task {
    body(
      script(src := "/main.js", `type` := "module"),
      div(id := appRoot),
      raw(refreshScript)
    ).render
  }

  def refreshScript: String =
    script(
      raw("""
        |const sse = new EventSource('/refresh/v1/sse');
        |sse.addEventListener('message', (e) => {
        |  const msg = JSON.parse(e.data)
        |  if ('KeepAlive' in msg)
        |      console.log("KeepAlive")
        |
        |  if ('PageRefresh' in msg) {
        |    console.log("PageRefresh @ " + new Date().toISOString());
        |    location.reload();
        |  }
        |
        |});
        |""".stripMargin)
    ).render

  def indexHtml = Task {
    val doc =
      "<!doctype html>\n" +
        html(
          raw(indexHtmlHead()),
          raw(indexHtmlBody())
        ).render

    os.write.over(Task.dest / "index.html", doc)
    PathRef(Task.dest / "index.html")
  }

  def assetsDir =
    super.moduleDir / "assets"

  def withStyles = Task(true)

  def stylesAutoRefresh = Task(false)

  def assets = Task.Source {
    assetsDir
  }

  def hasLess = os.exists(assetsDir / "index.less")

  def port = Task {
    8080
  }

  def openBrowser = Task {
    true
  }

  def logLevel = Task {
    "warn"
  }

  def dezombify = Task {
    true
  }

  def siteGen = Task {
    val assets_ = assets()
    val path = fastLinkJS().dest.path
    os.copy.over(indexHtml().path, Task.dest / "index.html")
    os.copy(assets_.path, Task.dest, mergeFolders = true)
    updateServer.publish1(Task.log.info("publish update")).unsafeRunSync()
    (Task.dest.toString(), assets_.path.toString(), path.toString())
  }

  def siteGenFull = Task {
    val assets_ = assets()
    val path = fullLinkJS().dest.path
    os.copy.over(indexHtml().path, Task.dest / "index.html")
    os.copy(assets_.path, Task.dest, mergeFolders = true)
    (Task.dest.toString(), assets_.path.toString(), path.toString())
  }

  def lcs = Task.Worker {
    val (site, assets, js) = siteGen()
    Task.log.info("Gen lsc")
    LiveServerConfig(
      baseDir = None,
      outDir = Some(js),
      port =
        com.comcast.ip4s.Port.fromInt(port()).getOrElse(throw new IllegalArgumentException(s"invalid port: ${port()}")),
      indexHtmlTemplate = Some(site),
      buildTool = io.github.quafadas.sjsls.NoBuildTool(), // Here we are a slave to the build tool
      openBrowserAt = "/index.html",
      preventBrowserOpen = !openBrowser(),
      dezombify = dezombify(),
      logLevel = logLevel(),
      customRefresh = Some(updateServer)
    )
  }

  def serve = Task.Worker {

    Task.log.info(lcs().toString)
    BuildCtx.withFilesystemCheckerDisabled {
      new RefreshServer(lcs())
    }
  }

  def serveFull = Task.Worker {
    val (site, assets, js) = siteGenFull()
    val address = InetSocketAddress(8081)
    val path = Path.of(site)
    val server = SimpleFileServer.createFileServer(address, path, SimpleFileServer.OutputLevel.VERBOSE)
    server.start()
    new AutoCloseable {
      override def close(): Unit = {
      server.stop(0)
      }
    }
  }

  class RefreshServer(lcs: LiveServerConfig) extends AutoCloseable:
    val server = io.github.quafadas.sjsls.LiveServer.main(lcs).allocated

    server.map(_._1).unsafeRunSync()

    override def close(): Unit =
      // This is the shutdown hook for http4s
      println("Shutting down server...")
      server.flatMap(_._2).unsafeRunSync()
    end close
  end RefreshServer
end ScalaJsRefreshModule
