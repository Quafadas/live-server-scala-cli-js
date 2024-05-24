import scalatags.Text.all.*

import fs2.io.file.Path

def lessStyle(withStyles: Boolean): Seq[Modifier] =
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

val refreshScript = script(raw("""const sse = new EventSource('/api/v1/sse');
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

def injectModulePreloads(modules: Seq[(Path, String)], template: String) =
  val modulesStrings =
    for
      m <- modules
      if m._1.toString.endsWith(".js")
    yield link(rel := "modulepreload", href := s"${m._1}?hash=${m._2}").render

  val modulesStringsInject = modulesStrings.mkString("\n", "\n", "\n")
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

def vanillaTemplate(withStyles: Boolean) = html(
  head(
  ),
  body(
    lessStyle(withStyles),
    script(src := "main.js", `type` := "module"),
    div(id := "app"),
    refreshScript
  )
)
