# Plugin

sjsls also has a mill plugin (requires mill > 1.1.4). This plugin aims to make it very simple to develop and deploy a scala-js project.

## build.mill

```scala
//| mill-version: 1.1.5
//| mvnDeps:
//| - io.github.quafadas:sjsls_plugin_mill1_3.8:@VERSION@
```
## package.mill

```scala
package build.example

import mill.*, scalalib.*, scalajslib.*
import mill.scalajslib.api.*
import io.github.quafadas.ScalaJsWebAppModule

object `package` extends ScalaJsWebAppModule:
  def scalaVersion = "3.8.2"

  def mvnDeps = Seq(
    mvn"io.github.nguyenyou::webawesome-laminar::3.0.0"
  )

  override def moduleSplitStyle = ModuleSplitStyle.SmallModulesFor(
    "mathlify",
    "mathlify.example"
  )

  override def scalaJSImportMap = Seq(
    ESModuleImportMapping.Prefix("@awesome.me/webawesome/dist/", "https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.4.0/dist-cdn/")
  )

  override def externalStylesheets = Seq(
    "https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.4.0/dist-cdn/styles/webawesome.css"
  )
end `package`
```
This build encapsulates the standard `ScalaJsWebAppModule` workflow:

- `mill -w example.serve` - reload on change
- `mill example.assembleSite` - build a self-contained static site directory
- `mill show example.serveCommand` - print a simple command to smoke-test the assembled site

## What `ScalaJsWebAppModule` provides

`ScalaJsWebAppModule` combines the live-reload server from `ScalaJsRefreshModule` with the in-memory content hashing
support from `InMemoryFastLinkHashScalaJSModule`.

In practice that means:

- development HTML is generated from the Scala.js linker report. `<script type="module">` tags point at the real hashed filenames.
- Hashed files are served with immutable caching headers, so the browser can reuse them across edits when they don't change.
- style changes hot reload
- split-module output works without hard-coding `/main.js`
- the dev server serves hashed linker output from memory for fast reload cycles
- `assembleSite` emits a static directory containing `index.html`, the hashed JS artifacts, and any assets

## Lower-level plugin: `ScalaJsRefreshModule`

If you only want the development server and generated `index.html`, `ScalaJsRefreshModule` is the smaller building
block.

It provides:

- `serve` for live development against `fastLinkJS`
- generated HTML with `<script type="module">` tags derived from the linker report
- the browser refresh script and SSE wiring
- optional copying of an `assets` directory into the generated site

`ScalaJsWebAppModule` builds on top of it by adding in-memory content hashing and `assembleSite` for a static output
directory.

### Development Workflow

`mill -w example.serve`

- mill watches for file change. It emits a pulse (into an `fs2.Topic`) after `fastLinkJS` is successful ...
- to the live server. Live server emits a server side event to the client.
- Client has a js script in it's html listening for these events which triggers a page refresh
- browser sends back it's page request and gets `index.html`
- index.html contains references to the hashed JS modules (or wasm companion files) reported by Scala.js.
- Browser resolves the ESModule graph...
- The output has already been processed so intra-module imports reference the content-hashed filenames.
- Many Scala.js modules do not change between edits. They are served with immutable caching, so the browser can reuse them without re-downloading everything.
- In the example above, we reference a concrete version of webawesome by rewriting the import to a CDN URL. That dependency is also cacheable independently of the local app bundle.

Page reload is _very_ fast.

### Production Workflow

The `assembleSite` command:
- runs fullLinkJS
- generates an index.html with the correct content-hashed references to the linked JS files
- copies the linked JS files into the task destination directory
- copies index.html into the task destination directory
- copies assets into the task destination directory when `assetsDir` exists

You can then serve that output locally with the command printed by `mill show example.serveCommand`.



