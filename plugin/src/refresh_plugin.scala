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

trait ScalaJsWebAppModule extends FileBasedContentHashScalaJSModule with ScalaJsRefreshModule:

  def publish = Task {
    val report = minified()
    val srcDir = report.dest.path

    val scriptTags = report
      .publicModules
      .map { m => script(src := s"/${m.jsFileName}", `type` := "module") }

    val bodyHtml = body(
      frag(scriptTags.toSeq*),
      div(id := appRoot)
    ).render

    val doc =
      "<!doctype html>\n" +
        html(
          raw(indexHtmlHead()),
          raw(bodyHtml)
        ).render

    os.write(Task.dest / "index.html", doc)
    os.list(srcDir).foreach(f => os.copy(f, Task.dest / f.last, replaceExisting = true))
    if os.exists(assetsDir) then os.copy(assets().path, Task.dest, mergeFolders = true)
    end if
    PathRef(Task.dest)
  }

  /**
   * mill show project.serveCommand will emit a string you can use to spin up a simple Java Server which will test the publishe site.
   */
  def serveCommand = Task {
    val publishDir = publish().path
    s"jwebserver -d $publishDir -p 8080"
  }



trait ScalaJsRefreshModule extends ScalaJSConfigModule:

  lazy val updateServer = Topic[IO, Unit].unsafeRunSync()

  override def moduleKind: Simple[ModuleKind] = ModuleKind.ESModule

  def indexHtmlHead = Task {
    head(
      meta(charset := "utf-8"),
      meta(name := "viewport", content := "width=device-width, initial-scale=1"),
      title := titleString,
      externalStylesheets.map {
        hrefLink =>
          link(href := hrefLink, rel := "stylesheet")
      },
      link(rel := "icon", href := "data:image/png;base64,iVBORw0KGgo="),//avoid favicon error
      if hasLess then script(src := "https://cdn.jsdelivr.net/npm/less@4.6.3/dist/less.min.js") else frag(),
      if hasLess then link(rel := "stylesheet/less", href := "/index.less", `type` := "text/css") else frag(),
      if stylesAutoRefresh() && hasLess then script("less.watch();") else frag()
    ).render
  }

  def externalStylesheets = Seq.empty[String]

  def appRoot: String = "app"

  def titleString: String = "App"

  def indexHtmlBody = Task {
    val report = fastLinkJS()
    val scriptTags = report
      .publicModules
      .map {
        m =>
          script(src := s"/${m.jsFileName}", `type` := "module")
      }
    body(
      frag(scriptTags.toSeq*),
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

  /** Path to write server logs to. When set, logs go to this file instead of
    * the console — useful because Mill watch mode captures stdout/stderr
    * per-task, making background server output invisible between evaluations.
    * Example override: `def logFile = Task { Some("/tmp/sjsls.log") }`
    */
  def logFile: Task[Option[PathRef]] = Task {
    None
  }

  def devToolsUuid = Task {
    java.util.UUID.randomUUID().toString
  }

  def siteGen = Task {
    val path = fastLinkJS().dest.path
    os.copy.over(indexHtml().path, Task.dest / "index.html")
    if os.exists(assetsDir) then os.copy(assets().path, Task.dest, mergeFolders = true)
    end if
    updateServer.publish1(Task.log.info("publish update")).unsafeRunSync()
    (Task.dest.toString(), path.toString())
  }

  def siteGenFull = Task {
    val path = fullLinkJS().dest.path
    os.copy.over(indexHtml().path, Task.dest / "index.html")
    if os.exists(assetsDir) then os.copy(assets().path, Task.dest, mergeFolders = true)
    end if
    (Task.dest.toString(), path.toString())
  }

  def lcs = Task.Worker {
    val (site, js) = siteGen()
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
      logFile = logFile().fold[Option[String]](None)(p => Some(p.path.toString)),
      customRefresh = Some(updateServer),
      devToolsWorkspace = Some((moduleDir.toString(), devToolsUuid()))
    )
  }

  def serve = Task.Worker {

    Task.log.info(lcs().toString)
    BuildCtx.withFilesystemCheckerDisabled {

      new RefreshServer(lcs())
    }
  }

  class RefreshServer(lcs: LiveServerConfig) extends AutoCloseable:
    // Allocate exactly once: run the IO and hold on to both the server and its
    // release action.  The original code stored the IO description and
    // re-evaluated it on close(), which started a second server instance and
    // released that one instead of the original.
    private val (_, release) =
      io.github.quafadas.sjsls.LiveServer.main(lcs).allocated.unsafeRunSync()

    override def close(): Unit =
      println("Shutting down server...")
      release.unsafeRunSync()
    end close
  end RefreshServer
end ScalaJsRefreshModule
