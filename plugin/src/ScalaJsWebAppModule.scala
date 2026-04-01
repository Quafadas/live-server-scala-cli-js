package io.github.quafadas.sjsls

import mill.PathRef
import mill.Task
import mill.scalajslib.api.ModuleSplitStyle
import mill.scalajslib.api.ESModuleImportMapping
import mill.api.Task.Simple

// trait ScalaJsWebAppModule extends FileBasedContentHashScalaJSModule with ScalaJsRefreshModule:

//   def smallModulesFor = Task(
//     Seq.empty[String]
//   )

//   def importMap: Task.Simple[Map[String, String]] = Task(Map.empty[String, String])

//   override def scalaJSImportMap: Simple[Seq[ESModuleImportMapping]] = Task {
//     importMap().map { case (importName, path) => ESModuleImportMapping.Prefix(importName, path) }.toSeq
//   }

//   override def moduleSplitStyle: Task.Simple[ModuleSplitStyle] =
//     Task(ModuleSplitStyle.SmallModulesFor(smallModulesFor()*))

//   def publish = Task {
//     val report = fullLinkJS()
//     val minifiedDir = report.dest.path
//     val doc = fullDocHtml(indexHtmlHead(), bodyHtmlFromReport(report))

//     os.write(Task.dest / "index.html", doc)
//     os.list(minifiedDir).foreach(f => os.copy(f, Task.dest / f.last, replaceExisting = true))
//     if os.exists(assetsDir) then os.copy(assets().path, Task.dest, mergeFolders = true)
//     end if
//     PathRef(Task.dest)
//   }

//   /** mill show project.serveCommand will emit a string you can use to spin up a simple Java Server which will test the
//     * publishe site.
//     */
//   def serveCommand = Task {
//     val publishDir = publish().path
//     s"jwebserver -d  \"$publishDir\" -p 8080"
//   }
// end ScalaJsWebAppModule
trait ScalaJsWebAppModule extends InMemoryFastLinkHashScalaJSModule with ScalaJsRefreshModule:

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

  /** mill show project.serveCommand will emit a string you can use to spin up a simple Java Server which will test the
    * publishe site.
    */
  def serveCommand = Task {
    val publishDir = assembleSite().path
    s"jwebserver -d  \"$publishDir\" -p 8080"
  }
end ScalaJsWebAppModule
