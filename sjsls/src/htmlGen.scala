package io.github.quafadas.sjsls

import scalatags.Text.all.*

import fs2.io.file.Path
import cats.effect.kernel.Ref
import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import scribe.Scribe
import org.http4s.Response
import java.time.ZonedDateTime
import org.http4s.scalatags.*
import org.http4s.Status
import cats.syntax.all.*

def generatedIndexHtml(injectStyles: Boolean, modules: Ref[IO, Map[String, String]], zdt: ZonedDateTime)(
    logger: Scribe[IO]
) =
  StaticHtmlMiddleware(
    HttpRoutes.of[IO] {
      case req @ GET -> Root =>
        logger.trace("Generated index.html") >>
          vanillaTemplate(injectStyles, modules).flatMap: html =>
            userBrowserCacheHeaders(Response[IO]().withEntity(html).withStatus(Status.Ok), zdt, injectStyles)

    },
    injectStyles,
    zdt
  )(logger).combineK(
    StaticHtmlMiddleware(
      HttpRoutes.of[IO] {
        case GET -> Root / "index.html" =>
          vanillaTemplate(injectStyles, modules).flatMap: html =>
            userBrowserCacheHeaders(Response[IO]().withEntity(html).withStatus(Status.Ok), zdt, injectStyles)

      },
      injectStyles,
      zdt
    )(logger)
  )

def lessStyle(withStyles: Boolean): Seq[Modifier] =
  if withStyles then
    Seq(
      link(
        rel := "stylesheet/less",
        `type` := "text/css",
        href := "/index.less"
      ),
      script(
        raw(
          """less = {env: "development",async: true,fileAsync: true,dumpLineNumbers: "comments",relativeUrls: false};"""
        )
      ),
      script(src := "https://cdn.jsdelivr.net/npm/less"),
      script("less.watch();")
    )
  else Seq.empty

val refreshScript = script(raw("""const sse = new EventSource('/refresh/v1/sse');
sse.addEventListener('message', (e) => {
  const msg = JSON.parse(e.data)

  if ('KeepAlive' in msg)
      console.log("KeepAlive")

  if ('PageRefresh' in msg)
      location.reload()});"""))

/*
 * create an html template with that has a head, which includes script tags, that have modulepreload enabled
 */

// def generateHtml(modules: Seq[(Path, String)]) = (template: String => String) =>
//   template(makeHeader(modules, true).render)

def injectRefreshScript(template: String) =
  val bodyCloseTag = "</body>"
  val insertionPoint = template.indexOf(bodyCloseTag)

  val newHtmlContent = template.substring(0, insertionPoint) +
    refreshScript.render + "\n" +
    template.substring(insertionPoint)

  newHtmlContent

end injectRefreshScript

def injectModulePreloads(ref: Ref[IO, Map[String, String]], template: String) =
  val preloads = makeInternalPreloads(ref)
  preloads.map: modules =>
    val modulesStringsInject = modules.mkString("\n", "\n", "\n")
    val headCloseTag = "</head>"
    val insertionPoint = template.indexOf(headCloseTag)

    val newHtmlContent = template.substring(0, insertionPoint) +
      modulesStringsInject +
      template.substring(insertionPoint)
    newHtmlContent

end injectModulePreloads

def makeHeader(modules: Seq[(Path, String)], withStyles: Boolean) =
  val scripts =
    for
      m <- modules
      if m._1.toString.endsWith(".js")
    yield link(rel := "modulepreload", href := s"${m._1}?hash=${m._2}")

  html(
    head(
      meta(
        httpEquiv := "Cache-control",
        content := "no-cache, no-store, must-revalidate"
      ),
      scripts
    ),
    body(
      lessStyle(withStyles),
      script(src := "main.js", `type` := "module"),
      div(id := "app"),
      // script(src := "main"),
      refreshScript
    )
  )
end makeHeader

def makeInternalPreloads(ref: Ref[IO, Map[String, String]]) =
  val keys = ref.get.map(_.toSeq)
  keys.map {
    modules =>
      for
        m <- modules
        if m._1.toString.endsWith(".js") && m._1.toString.startsWith("internal")
      yield link(rel := "modulepreload", href := s"${m._1}?h=${m._2}")
      end for
  }

end makeInternalPreloads

def vanillaTemplate(withStyles: Boolean, ref: Ref[IO, Map[String, String]]) =
  val preloads = makeInternalPreloads(ref)
  preloads.map: modules =>
    html(
      head(
        meta(
          httpEquiv := "Cache-control",
          content := "no-cache, no-store, must-revalidate",
          modules
        )
      ),
      body(
        lessStyle(withStyles),
        script(src := "/main.js", `type` := "module"),
        div(id := "app"),
        refreshScript
      )
    )
end vanillaTemplate
