# Config

The server is a CLI. It has a number of flags that can be used to configure it. Here is the current list of flags and what they do.

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
