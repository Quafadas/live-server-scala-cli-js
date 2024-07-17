# Config

The server is a CLI. It has a number of flags that can be used to configure it. Here is the current list of flags and what they do. You can see these flags by running ` --help` in your terminal.

```
cs launch io.github.quafadas:live-server-scala-cli-js_3:{{projectVersion}} -- --help

```


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

# Examples;

## Scala-cli single module

Fire up a terminal in projectDir

```
| projectDir
├── hello.scala
```

```sh
cs launch io.github.quafadas::sjsls:{{projectVersion}}
```
This is the classic [viteless](https://github.com/Quafadas/viteless/tree/main) example

## Styles baby, make it look good!

With styles.

```
| projectDir
├── hello.scala
├── styles
├── ├── index.less
```
Run

```sh
cs launch io.github.quafadas::sjsls:{{projectVersion}} -- --styles-dir --fully/qualified/dir/to/styles
```

## Did I mention I want a full blown SPA?

With client side routing under `/app`?

```
| projectDir
├── hello.scala
├── styles
├── ├── index.less
```
Run

```sh
cs launch io.github.quafadas::sjsls:{{projectVersion}} -- --client-routes-prefix app
```

## Stop generating my HTML. I want to bring my own.

Okay.

```
| projectDir
├── hello.scala
├── assets
├── ├── index.less
├── ├── index.html
```
With
```sh
cs launch io.github.quafadas::sjsls:{{projectVersion}} -- --path-to-index-html fully/qualified/path/to/assets
```

Note: if you're brining your own html, drop the `--styles` flag - reference `index.less` from your html and read [docs](https://lesscss.org) to get it working in browser.

***
You need to include this javascript script tag in the body html - otherwise no page refresh.

```
<script>
    const sse = new EventSource("/refresh/v1/sse");
    sse.addEventListener("message", (e) => {
    const msg = JSON.parse(e.data);

    if ("KeepAlive" in msg) console.log("KeepAlive");

    if ("PageRefresh" in msg) location.reload();
    });
</script>
```
***

## Full stack - need proxy to backend

With a backend running on `8080` and a frontend on `3000`, it is configured that requests beginning with `api` are proxied to localhost:8080.

Also, we're now using mill. We need to tell the cli the frontend module name and the directory the compiles JS ends up in.

```sh
cs launch io.github.quafadas::sjsls:{{projectVersion}} -- \
    --path-to-index-html /Users/simon/Code/mill-full-stack/frontend/ui \
    --build-tool mill \
    --mill-module-name frontend \
    --port 3000 \
    --out-dir /Users/simon/Code/mill-full-stack/out/frontend/fastLinkJS.dest \
    --proxy-prefix-path /api \
    --proxy-target-port 8080 \

```