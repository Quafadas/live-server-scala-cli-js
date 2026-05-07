package io.github.quafadas.sjsls

import cats.effect.IO
import cats.effect.ExitCode

import fs2.io.file.Files
import fs2.io.file.Path
import fs2.io.file.Flags

private[sjsls] val importMapJson: String =
  """|{
     |  "imports": {
     |    "@ms@2.1.3/+esm": "https://cdn.jsdelivr.net/npm/ms@2.1.3/+esm"
     |  }
     |}
     |""".stripMargin

private[sjsls] val projectScala: String =
  """|//> using scala 3.7.1
     |//> using platform js
     |
     |//> using jsVersion 1.19.0
     |
     |//> using dep org.scala-js::scalajs-dom::2.8.0
     |//> using dep com.raquo::laminar::17.2.1
     |
     |//> using jsModuleKind es
     |//> using jsModuleSplitStyleStr smallmodulesfor
     |//> using jsSmallModuleForPackage webapp
     |//> using jsEmitSourceMaps true
     |
     |//> using jsEsModuleImportMap importMap.json
     |
     |package webapp
     |
     |import org.scalajs.dom
     |import org.scalajs.dom.document
     |import scala.scalajs.js
     |import scala.scalajs.js.annotation._
     |import com.raquo.laminar.api.L.{*, given}
     |
     |@main
     |def main: Unit =
     |  renderOnDomContentLoaded(
     |    dom.document.getElementById("app"),
     |    app
     |  )
     |
     |def app =
     |  val hiVar = Var("2 days") // Local state
     |  div(
     |    h1(
     |      s"ScalaJS quickstart"
     |    ),
     |    p("A trivial laminar example"),
     |    // https://demo.laminar.dev/app/form/controlled-inputs
     |
     |    p("Of a tiny, npm free scalaJS app."),
     |    ul(
     |      li("A single scala file"),
     |      li("Link and reload on change"),
     |      li("Instant style refresh"),
     |      li("A simple facade and import map for ms -  ",
     |        input(
     |              typ := "text",
     |              controlled(
     |                value <-- hiVar.signal,
     |                onInput.mapToValue --> hiVar.writer
     |              )
     |            ),
     |        child <-- hiVar.signal.map(s => s"is ${ms(s)} ms")
     |      )
     |    )
     |
     |  )
     |
     |@js.native
     |@JSImport("@ms@2.1.3/+esm", JSImport.Default)
     |object ms extends js.Object {
     |	def apply(s: Double): String = js.native
     |	def apply(s: String): Double = js.native
     |}
     |""".stripMargin

private[sjsls] val indexLess: String =
  """|@accent: #5c6bc0;
     |@text: #2d2d2d;
     |@muted: #666;
     |@bg: #f7f8fc;
     |
     |*, *::before, *::after {
     |  box-sizing: border-box;
     |}
     |
     |body {
     |  margin: 0;
     |  padding: 2rem;
     |  background: @bg;
     |  color: @text;
     |  font-family: system-ui, -apple-system, sans-serif;
     |  font-size: 1rem;
     |  line-height: 1.6;
     |}
     |
     |#app {
     |  max-width: 640px;
     |  margin: 0 auto;
     |  background: #fff;
     |  border-radius: 10px;
     |  box-shadow: 0 2px 16px rgba(0, 0, 0, 0.08);
     |  padding: 2.5rem 3rem;
     |}
     |
     |h1 {
     |  color: @accent;
     |  font-size: 1.8rem;
     |  margin: 0 0 0.5rem;
     |  letter-spacing: -0.02em;
     |}
     |
     |p {
     |  color: @muted;
     |  margin: 0.4rem 0;
     |}
     |
     |ul {
     |  margin: 1.2rem 0 0;
     |  padding-left: 1.4rem;
     |
     |  li {
     |    margin-bottom: 0.5rem;
     |  }
     |}
     |
     |input[type="text"] {
     |  border: 1px solid #d0d4e8;
     |  border-radius: 5px;
     |  padding: 0.2rem 0.5rem;
     |  font-size: 0.95rem;
     |  outline: none;
     |  transition: border-color 0.2s;
     |  margin: 0 0.4rem;
     |
     |  &:focus {
     |    border-color: @accent;
     |  }
     |}
     |""".stripMargin

private[sjsls] def scaffoldProject(projectName: String): IO[ExitCode] =
  given filesInstance: Files[IO] = Files.forAsync[IO]
  val cwd = Path(System.getProperty("user.dir"))
  val projDir = cwd / projectName
  val assetsDir = projDir / "assets"

  def writeFile(path: Path, content: String): IO[Unit] =
    fs2
      .Stream
      .emit(content)
      .through(fs2.text.utf8.encode)
      .through(filesInstance.writeAll(path, Flags.Write))
      .compile
      .drain

  for
    _ <- filesInstance.createDirectories(assetsDir)
    _ <- writeFile(projDir / "importMap.json", importMapJson)
    _ <- writeFile(projDir / "project.scala", projectScala)
    _ <- writeFile(assetsDir / "index.less", indexLess)
    _ <- IO.println(
      s"""|Scaffolded app. To run it:
          |  cd $projectName
          |  sjsls --styles-dir assets""".stripMargin
    )
  yield ExitCode.Success
  end for
end scaffoldProject
