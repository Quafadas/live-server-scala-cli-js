package io.github.quafadas.sjsls

import java.time.ZonedDateTime

import scalatags.Text.TypedTag
import scalatags.Text.all.*

import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.Status
import org.http4s.dsl.io.*
import org.http4s.scalatags.*

import fs2.io.file.Path

import scribe.Scribe

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*

import _root_.io.circe.Json

private def generatedIndexHtml(
    injectStyles: Boolean,
    injectCssFile: Option[String],
    modules: Ref[IO, Map[String, String]],
    zdt: ZonedDateTime,
    attemptPreload: Boolean
)(
    logger: Scribe[IO]
) =
  StaticHtmlMiddleware(
    HttpRoutes.of[IO] {
      case req @ GET -> Root =>
        logger.trace("Generated index.html") >>
          vanillaTemplate(
            injectStyles,
            modules,
            attemptPreload,
            injectStyles,
            Some("/main.js"),
            injectCssFile
          ).flatMap: html =>
            userBrowserCacheHeaders(Response[IO]().withEntity(html).withStatus(Status.Ok), zdt, injectStyles)

    },
    injectStyles,
    zdt
  )(logger).combineK(
    StaticHtmlMiddleware(
      HttpRoutes.of[IO] {
        case GET -> Root / "index.html" =>
          vanillaTemplate(injectStyles, modules, attemptPreload, injectStyles, Some("/main.js"), injectCssFile).flatMap:
            html => userBrowserCacheHeaders(Response[IO]().withEntity(html).withStatus(Status.Ok), zdt, injectStyles)

      },
      injectStyles,
      zdt
    )(logger)
  )

private def lessStyle(withStyles: Boolean, stylesRefresh: Boolean): Seq[Modifier] =
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
      if stylesRefresh then script("less.watch();") else frag()
    )
  else Seq.empty

val refreshScript = script(raw("""const sse = new EventSource('/refresh/v1/sse');
sse.addEventListener('message', (e) => {
  const msg = JSON.parse(e.data)

  if ('KeepAlive' in msg)
      console.log("KeepAlive")

  if ('PageRefresh' in msg)
      location.reload()

  if ('AssetRefresh' in msg) {
    const path = msg.AssetRefresh.path;
    const links = document.querySelectorAll('link[rel="stylesheet"]');
    links.forEach(link => {
      if (link.href.includes(path)) {
        const url = new URL(link.href);
        url.searchParams.set('t', Date.now());
        link.href = url.toString();
      }
    });
  }
});"""))

/*
 * create an html template with that has a head, which includes script tags, that have modulepreload enabled
 */

// def generateHtml(modules: Seq[(Path, String)]) = (template: String => String) =>
//   template(makeHeader(modules, true).render)

private def injectRefreshScript(template: String) =
  val bodyCloseTag = "</body>"
  val insertionPoint = template.indexOf(bodyCloseTag)

  val newHtmlContent = template.substring(0, insertionPoint) +
    refreshScript.render + "\n" +
    template.substring(insertionPoint)

  newHtmlContent

end injectRefreshScript

private def injectModulePreloads(ref: Ref[IO, Map[String, String]], template: String) =
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

private def makeHeader(modules: Seq[(Path, String)], withStyles: Boolean, attemptPreload: Boolean = false) =
  val scripts =
    for
      m <- modules
      if m._1.toString.endsWith(".js")
    yield link(
      rel := "modulepreload",
      href := s"${m._1}?hash=${m._2}"
    )

  html(
    head(
      meta(
        httpEquiv := "Cache-control",
        content := "no-cache, no-store, must-revalidate"
      ),
      if attemptPreload then scripts else ()
    ),
    body(
      lessStyle(withStyles, true),
      script(src := "main.js", `type` := "module"),
      div(id := "app"),
      // script(src := "main"),
      refreshScript
    )
  )
end makeHeader

private def makeInternalPreloads(ref: Ref[IO, Map[String, String]]) =
  val keys = ref.get.map(_.toSeq)
  keys.map {
    modules =>
      for
        m <- modules
        if m._1.toString.endsWith(".js") && m._1.toString.startsWith("internal")
      // A preload must use the URL the import map resolves to, or the browser fetches the module twice.
      yield
        if fileNameIsHashed(m._2) then link(rel := "modulepreload", href := s"/${m._2}")
        else link(rel := "modulepreload", href := s"${m._1}?h=${m._2}")
      end for
  }

end makeInternalPreloads

private def fileNameIsHashed(name: String) = name.matches(".*\\.[a-f0-9]{8,}\\..*")

/** The original -> hashed JS module names in `ref`. Only populated when serving content-hashed output from memory. */
private def importMapEntries(ref: Ref[IO, Map[String, String]]): IO[Map[String, String]] =
  ref.get.map(_.filter((name, hashed) => name.endsWith(".js") && fileNameIsHashed(hashed)))

/** An import map which points each JS module at its content-hashed file, e.g.
  * `{"imports":{"./internal-foo.js":"./internal_foo.0123456789abcdef.js"}}`.
  *
  * Scala.js modules import each other by their original names, so with this map in the page they can be hashed
  * independently: a change to one module changes only that module's URL. It must appear before any module script.
  *
  * @param basePath
  *   prefix for both sides of the map, e.g. `/` or `./`, matching how the page refers to its scripts
  */
def importMapScript(entries: Map[String, String], basePath: String): String =
  val imports = entries.toSeq.sorted.map((name, hashed) => s"$basePath$name" -> Json.fromString(s"$basePath$hashed"))
  s"""<script type="importmap">${Json.obj("imports" -> Json.obj(imports*)).noSpaces}</script>"""
end importMapScript

/** Adds the import map to a user supplied index.html, straight after `<head>`. A page which already has an import map
  * is left alone, as merging maps isn't supported; it still works, via the unhashed module names.
  */
private def injectImportMap(ref: Ref[IO, Map[String, String]], template: String): IO[String] =
  importMapEntries(ref).map {
    entries =>
      val headOpen = "(?i)<head[^>]*>".r.findFirstMatchIn(template)
      (headOpen, entries.isEmpty || template.contains("importmap")) match
        case (Some(m), false) => template.patch(m.end, importMapScript(entries, "/"), 0)
        case _                => template
      end match
  }

def vanillaTemplate(
    styles: Boolean,
    stylesRefresh: Boolean,
    injectMain: Option[String] = None,
    injectStyleSheet: Option[String] = None
): String =
  val r = Ref.of[IO, Map[String, String]](Map.empty)
  r.flatMap(rf => vanillaTemplate(styles, rf, false, stylesRefresh, injectMain, injectStyleSheet).map(_.render))
    .unsafeRunSync()(using cats.effect.unsafe.implicits.global)
end vanillaTemplate

def vanillaTemplate(
    withStyles: Boolean,
    ref: Ref[IO, Map[String, String]],
    attemptPreload: Boolean,
    stylesRefresh: Boolean,
    injectMain: Option[String],
    injectStyleSheet: Option[String]
): IO[TypedTag[String]] =

  (makeInternalPreloads(ref), importMapEntries(ref)).mapN: (modules, entries) =>
    html(
      head(
        meta(
          httpEquiv := "Cache-control",
          content := "no-cache, no-store, must-revalidate"
        ),
        if entries.nonEmpty then raw(importMapScript(entries, "/")) else (),
        if attemptPreload then modules else ()
      ),
      body(
        lessStyle(withStyles, stylesRefresh),
        injectMain match
          case Some(main) =>
            // A script src isn't resolved through the import map, so point it at the hashed entry module directly
            val mainSrc = entries.get(main.stripPrefix("/")).fold(main)(hashed => s"/$hashed")
            script(src := mainSrc, `type` := "module")
          case None => frag(),
        injectStyleSheet match
          case Some(sheet) => link(rel := "stylesheet", href := sheet)
          case None        => frag(),
        div(id := "app"),
        refreshScript
      )
    )
end vanillaTemplate
