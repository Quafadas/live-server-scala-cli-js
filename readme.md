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

The dream, is for the CLI to be flexible enough to accomodate more complex scenarios as well.

```
Usage: LiveServer [--project-dir <string>] [--out-dir <string>] [--port <integer>] [--proxy-target-port <integer>] [--proxy-prefix-path <string>] [--log-level <string>] [--build-tool <string>] [--browse-on-open-at <string>] [--extra-build-args <string>]... [--mill-module-name <string>] [--path-to-index-html <string>] [--styles-dir <string>]

Scala JS live server

Options and flags:
    --help
        Display this help text.
    --version, -v
        Print the version number and exit.
    --project-dir <string>
        The fully qualified location of your project - e.g. c:/temp/helloScalaJS
    --out-dir <string>
        Where the compiled JS will be compiled to - e.g. c:/temp/helloScalaJS/.out. If no file is given, a temporary directory is created.
    --port <integer>
        The port you want to run the server on - e.g. 3000
    --proxy-target-port <integer>
        The port you want to forward api requests to - e.g. 8080
    --proxy-prefix-path <string>
        Match routes starting with this prefix - e.g. /api
    --log-level <string>
        The log level. info, debug, error, trace
    --build-tool <string>
        scala-cli or mill
    --browse-on-open-at <string>
        A suffix to localhost where we'll open a browser window on server start - e.g. /ui/greatPage OR just `/` for root
    --extra-build-args <string>
        Extra arguments to pass to the build tool
    --mill-module-name <string>
        Extra arguments to pass to the build tool
    --path-to-index-html <string>
        a path to a directory which contains index.html. The entire directory will be served as static assets
    --styles-dir <string>
        A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles
```

To be minimally viable for me personally,
- A Proxy server to the backend
- Style application
- Mill
- Some way, to integrate the outcome into a deployment pipline

As the project is somehwhat young, the approach these points may remain chaotic... your contribution or opinion on them is welcome :-).

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

## Providing your own HTML

You'll need to make sure it includes this script. Otherwise no reload on change.

```html
    <script>
      const sse = new EventSource("/api/v1/sse");
      sse.addEventListener("message", (e) => {
        const msg = JSON.parse(e.data);

        if ("KeepAlive" in msg) console.log("KeepAlive");

        if ("PageRefresh" in msg) location.reload();
      });
    </script>

```