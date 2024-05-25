# An experiment in a dev server for scala JS

Try and break the dependance on node / npm completely whilst retaining a sane developer experience for browser based scala-js development.

[Blogpost](https://quafadas.github.io/Whimsy/2024/05/22/Viteless.html)

## Goals

Replicate the "experience" of using vite with scala JS.

- Live reload / link on change
- Hot application of style (no page reload)
- Proxy server
- page open on start

## Contraints

- Scala cli to build frontend
- ESModule output (only)
- Third party ESModules via import map rather than npm
- Styles through LESS

## Assumptions

`cs`, `scala-cli` and `mill` are readily available on the path.
The entry point for styles is `index.less`, and that file exists in the styles directory. It can link to other style files.
App must be mounted to a div, with id `app`.

## Contributing

CI builds a container image which is ready to roll.

## TL:DR

```sh
touch helloScalaJS.scala
```

```scala

//> using scala 3.4.2
//> using platform js

//> using dep org.scala-js::scalajs-dom::2.8.0
//> using dep com.raquo::laminar::17.0.0

//> using jsModuleKind es
//> using jsModuleSplitStyleStr smallmodulesfor
//> using jsSmallModuleForPackage webapp

package webapp

import org.scalajs.dom
import org.scalajs.dom.document
import com.raquo.laminar.api.L.{*, given}

@main
def main: Unit =
  renderOnDomContentLoaded(
    dom.document.getElementById("app"),
    interactiveApp
  )

def interactiveApp =
  val hiVar = Var("Wor")
  div(
    h1(
      s"This was more.scala",
      child.text <-- hiVar.signal
    ),
    p("This asdf df"),
    // https://demo.laminar.dev/app/form/controlled-inputs
    input(
      typ := "text",
      controlled(
        value <-- hiVar.signal,
        onInput.mapToValue --> hiVar.writer
      )
    )
  )
```

```sh
cs launch io.github.quafadas:live-server-scala-cli-js_3:0.0.11
```

The intention, is for the simple case to be zero configuration. The below invocation makes the following assumptions;

- cs (coursier) is on the path
- You are using scala-cli and it is on the path
- You are happy to serve your application on port 3000
- You wish a browser window to open at the root of the application
- You are invoking it from the root of a directory containing a valid scala JS project that is configured to use ES modules.
- Your application, will mount in a div with id `app`.

The file above is a one file example of such a project, satisfying these constraints.
