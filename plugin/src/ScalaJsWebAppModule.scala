package io.github.quafadas.sjsls

import mill.PathRef
import mill.Task
import mill.api.Task.Simple

/** Convenience trait for Scala.js browser applications that need both live reload during development and content-hashed
  * artifacts for deployment.
  *
  * Mix this into a Mill `ScalaJSModule` to get three pieces of behaviour wired together:
  *
  *   1. `serve` starts the live development server inherited from [[ScalaJsRefreshModule]].
  *   1. `fastLinkJS` output is content-hashed in memory via [[InMemoryFastLinkHashScalaJSModule]], so the generated
  *      `index.html` always references the real linker output filenames.
  *   1. [[assembleSite]] builds a static site directory containing `index.html`, all hashed linker output, and any
  *      files from `assetsDir`, ready to serve with a plain static file server.
  *
  * Compared with using [[ScalaJsRefreshModule]] on its own, this trait does not assume a fixed `/main.js` entrypoint.
  * Script tags are generated from the Scala.js linker [[mill.scalajslib.api.Report]], which means split modules and
  * content-hashed filenames work without additional configuration.
  *
  * The live server is configured to serve linker output directly from memory. On successful `fastLinkJS` runs it
  * republishes the generated HTML and emits refresh notifications through the inherited `updateServer` topic, which
  * keeps browser reloads fast while still reflecting hashed filenames.
  *
  * @example
  *   {{ object webapp extends ScalaJsWebAppModule { def scalaVersion = "3.3.6" } }}
  *
  * Typical commands:
  *
  *   - `mill -w webapp.serve` for local development with automatic reload.
  *   - `mill webapp.assembleSite` to emit a self-contained static site directory.
  *   - `mill show webapp.serveCommand` to print a simple command for serving the assembled site.
  */
trait ScalaJsWebAppModule extends InMemoryFastLinkHashScalaJSModule with ScalaJsRefreshModule:

  /** Live-server configuration for this module.
    *
    * The returned [[LiveServerConfig]] serves an `index.html` generated from the latest hashed linker report and reads
    * JS assets from the in-memory hashed output map instead of a directory on disk.
    */
  override def lcs = Task.Worker {
    val (site, js) = siteGen()
    Task.log.info("Gen lsc (in-memory)")
    LiveServerConfig(
      baseDir = None,
      outDir = Some(js),
      port =
        com.comcast.ip4s.Port.fromInt(port()).getOrElse(throw new IllegalArgumentException(s"invalid port: ${port()}")),
      indexHtmlTemplate = Some(site),
      buildTool = io.github.quafadas.sjsls.NoBuildTool(),
      openBrowserAt = "/index.html",
      preventBrowserOpen = !openBrowser(),
      dezombify = dezombify(),
      logLevel = logLevel(),
      logFile = logFile().fold[Option[String]](None)(p => Some(p.path.toString)),
      customRefresh = Some(updateServer),
      devToolsWorkspace = Some((moduleDir.toString(), devToolsUuid())),
      inMemoryFiles = Some(hashedOutputFiles)
    )
  }

  /** Assemble a static site directory from the production-style linker output.
    *
    * This task runs [[fullLinkJS]], renders an `index.html` whose module script tags point at the hashed filenames in
    * the linker report, copies every linked artifact into `Task.dest`, and merges `assetsDir` when it exists.
    *
    * The result is suitable for deployment to any static file host or for local verification with the command from
    * [[serveCommand]].
    */
  def assembleSite = Task {
    val report = fullLinkJS()
    val minifiedDir = report.dest.path
    val doc = fullDocHtml(indexHtmlHead(), bodyHtmlFromReport(report))

    os.write(Task.dest / "index.html", doc)
    os.list(minifiedDir).foreach(f => os.copy(f, Task.dest / f.last, replaceExisting = true))
    if os.exists(assetsDir) then os.copy(assets().path, Task.dest, mergeFolders = true)
    end if
    PathRef(Task.dest)
  }

  /** Print a `jwebserver` command that serves the output of [[assembleSite]].
    *
    * This is intended as a quick smoke-test helper for the assembled site, not as a replacement for [[serve]] during
    * active development.
    */
  def serveCommand = Task {
    val publishDir = assembleSite().path
    s"jwebserver -d  \"$publishDir\" -p 8080"
  }
end ScalaJsWebAppModule
