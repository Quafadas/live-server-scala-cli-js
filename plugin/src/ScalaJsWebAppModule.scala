package io.github.quafadas

import scalatags.Text.all.*

import mill.api.Task.Simple
import mill.scalajslib.*
import mill.scalajslib.api.Report
import mill.PathRef
import mill.Task

trait ScalaJsWebAppModule extends FileBasedContentHashScalaJSModule with ScalaJsRefreshModule:

  def publish = Task {
    val report = fullLinkJS()
    val minifiedDir = report.dest.path

    val scriptTags = report.publicModules.map(m => script(src := s"/${m.jsFileName}", `type` := "module"))

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
    os.list(minifiedDir).foreach(f => os.copy(f, Task.dest / f.last, replaceExisting = true))
    if os.exists(assetsDir) then os.copy(assets().path, Task.dest, mergeFolders = true)
    end if
    PathRef(Task.dest)
  }

  /** mill show project.serveCommand will emit a string you can use to spin up a simple Java Server which will test the
    * publishe site.
    */
  def serveCommand = Task {
    val publishDir = publish().path
    s"jwebserver -d  \"$publishDir\" -p 8080"
  }
end ScalaJsWebAppModule
