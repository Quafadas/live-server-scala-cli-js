import scalatags.Text.all.*

import fs2.io.file.Path

/*
 * create an html template with that has a head, which includes script tags, that have modulepreload enabled
 */

// def generateHtml(modules: Seq[(Path, String)]) = (template: String => String) =>
//   template(makeHeader(modules, true).render)

def injectModulePreloads(modules: Seq[(Path, String)], template: String) =
  val modulesStrings =
    for
      m <- modules
      if m._1.toString.endsWith(".js")
    yield link(rel := "modulepreload", href := s"${m._1}?hash=${m._2}").render

  // template(makeHeader(modules, true).render)
  modulesStrings
end injectModulePreloads

def makeHeader(modules: Seq[(Path, String)], withStyles: Boolean) =
  val scripts =
    for
      m <- modules
      if m._1.toString.endsWith(".js")
    yield link(rel := "modulepreload", href := s"${m._1}?hash=${m._2}")

  val lessStyle: Seq[Modifier] =
    if withStyles then
      Seq(
        link(
          rel := "stylesheet/less",
          `type` := "text/css",
          href := "index.less"
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

  html(
    head(
      meta(
        httpEquiv := "Cache-control",
        content := "no-cache, no-store, must-revalidate"
      ),
      scripts
    ),
    body(
      lessStyle,
      script(src := "main.js", `type` := "module"),
      div(id := "app"),
      // script(src := "main"),
      script(raw("""const sse = new EventSource('/api/v1/sse');
sse.addEventListener('message', (e) => {
  const msg = JSON.parse(e.data)

  if ('KeepAlive' in msg)
      console.log("KeepAlive")

  if ('PageRefresh' in msg)
      location.reload()
});"""))
    )
  )
end makeHeader
