import scalatags.Text.all.*
import fs2.io.file.Path
import scalatags.generic.TypedTag

/*
 * create an html template with that has a head, which includes script tags, that have modulepreload enabled
 */
def makeHeader(modules: Seq[(Path, String)], withStyles: Boolean) =
  val scripts =
    for (m <- modules)
      yield link(rel := "modulepreload", href := s"${m._1}?hash=${m._2}")

  val lessStyle: Seq[Modifier] =
    if withStyles then
      Seq(
        link(
          rel := "stylesheet/less",
          `type` := "text/css",
          href := "styles.less"
        ),
        script(
          raw(
            raw"""less = {env: "development",async: false,fileAsync: false,functions: {},dumpLineNumbers: "comments",relativeUrls: false};"""
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
      div(id := "app"),
      lessStyle,
      script(src := "main.js", `type` := "module"),
      script(raw(raw"""const sse = new EventSource('/api/v1/sse');
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
