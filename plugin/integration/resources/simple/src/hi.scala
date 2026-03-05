package webapp

import org.scalajs.dom
import org.scalajs.dom.document
import com.raquo.laminar.api.L.{*, given}

@main
def main: Unit =
  renderOnDomContentLoaded(
    dom.document.getElementById("app"),
    app
  )

def app =
  val hiVar = Var("Scala JS") // Local state
  div(
    h1(
      s"Hello ",
      child.text <-- hiVar.signal
    ),
    p("This page should reload on change. Check the justfile... for the command to run the server."),
    // https://demo.laminar.dev/app/form/controlled-inputs
    input(
      typ := "text",
      controlled(
        value <-- hiVar.signal,
        onInput.mapToValue --> hiVar.writer
      )
    )
  )