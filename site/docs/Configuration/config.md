# Config

The CLI launches an http server. It has a number of flags that can be used to configure it. Here is the current list of flags and what they do. You can see these flags by running ` --help` in your terminal.

```sh
cs launch io.github.quafadas::sjsls:latest.version -- --help

```


```
Usage:
    LiveServer --init <string>
    LiveServer [--project-dir <string>] [--out-dir <string>] [--port <integer>] [--proxy-target-port <integer>] [--proxy-prefix-path <string>] [--client-routes-prefix <string>] [--log-level <string>] [--build-tool <string>] [--browse-on-open-at <string>] [--prevent-browser-open] [--extra-build-args <string>]... [--mill-module-name <string>] [--styles-dir <string>] [--path-to-index-html <string>] [--build-tool-invocation <string>] [--inject-preloads] [--dezombify] [--log-file <string>] [--workspace-root <string>] [--workspace-uuid <string>]

Scala JS live server

Options and flags:
    --help
        Display this help text.
    --version, -v
        Print the version number and exit.
    --init <string>
        Scaffold a new ScalaJS starter project in a subfolder with the given name, then exit.
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
    --client-routes-prefix <string>
        Routes starting with this prefix  e.g. /app will return index.html. This enables client side routing via e.g. waypoint
    --log-level <string>
        The log level. info, debug, error, trace)
    --build-tool <string>
        scala-cli or mill
    --browse-on-open-at <string>
        A suffix to localhost where we'll open a browser window on server start - e.g. /ui/greatPage OR just `/` for root
    --prevent-browser-open
        prevent the browser from opening on server start
    --extra-build-args <string>
        Extra arguments to pass to the build tool
    --mill-module-name <string>
        Extra arguments to pass to the build tool
    --styles-dir <string>
        A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles
    --path-to-index-html <string>
        a path to a directory which contains index.html. The entire directory will be served as static assets
    --build-tool-invocation <string>
        This string will be passed to an fs2 process which invokes the build tool. By default it's 'scala-cli', or `mill`, and is assumed is on the path
    --inject-preloads
        Whether or not to attempt injecting module preloads into the index.html, potentially speeds up page load, but may not work with all servers and or cause instability in the refresh process.
    --dezombify
        Whether or not to attempt killing any processes that are using the specified port. Default: true
    --log-file <string>
        Write server logs to this file path instead of (or in addition to) the console. Useful when running under Mill watch mode where stdout/stderr is managed per-task.
    --workspace-root <string>
        Absolute path to the project source root for Chrome DevTools automatic workspace folder integration. Defaults to the current working directory when it contains at least one `.scala` file. Pass `none` to disable.
    --workspace-uuid <string>
        UUID to use in the Chrome DevTools workspace descriptor. If omitted a random UUID is generated at startup and reused for the lifetime of this CLI session.
```

# Examples;

## Scala-cli single module

Fire up a terminal in projectDir

```
| projectDir
├── hello.scala
```

```sh
cs launch io.github.quafadas::sjsls:@VERSION@
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
cs launch io.github.quafadas::sjsls:@VERSION@ -- --styles-dir styles
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
cs launch io.github.quafadas::sjsls:@VERSION@ -- --client-routes-prefix app
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
cs launch io.github.quafadas::sjsls:@VERSION@ -- --path-to-index-html fully/qualified/path/to/assets
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
cs launch io.github.quafadas::sjsls:@VERSION@ -- \
    --path-to-index-html /Users/simon/Code/mill-full-stack/frontend/ui \
    --build-tool mill \
    --mill-module-name frontend \
    --port 3000 \
    --out-dir /Users/simon/Code/mill-full-stack/out/frontend/fastLinkJS.dest \
    --proxy-prefix-path /api \
    --proxy-target-port 8080 \

```

## Static site, no build tool.

This would serve the static site build with the `docJar` tool.

```sh
C:\temp\live-server-scala-cli-js> cs launch io.github.quafadas::sjsls:@VERSION@ -- --path-to-index-html C:\\temp\\live-server-scala-cli-js\\out\\site\\live.dest\\site --build-tool none --browse-on-open-at /docs/index.html
```

You need to include this javascript script tag in the body html - otherwise no page refresh.

```html
<script>
    const sse = new EventSource("/refresh/v1/sse");
    sse.addEventListener("message", (e) => {
    const msg = JSON.parse(e.data);

    if ("KeepAlive" in msg) console.log("KeepAlive");

    if ("PageRefresh" in msg) location.reload();
    });
</script>
```