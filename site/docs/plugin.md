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
This build encapsulate most of what I want when making a webapp in the following two commands:

- `mill -w example.serve` - reload on change
- `mill example.publish` - build site

### Development Workflow

`mill -w example.serve`

- mill watches for file change. It emits a pulse (into an `fs2.Topic`) after `fastLinkJS` is successful ...
- to the live server. Live server emits a server side event to the client.
- Client has a js script in it's html listening for these events which triggers a page refresh
- browser sends back it's page request and gets `index.html`
- index.html contains a reference to a `main.<hash>.js` (or wasm) file.
- Browser resolves the ESModule graph...
    - Which has been processed in topological order and their import statements re-written to reference the content-hashed filenames.
    - Many scala JS modules are not changed. They are emitted with `immutable, public` Cache-Control headers server side. The browser loads these out of memory instead of making a network request.
    - In the example above, we reference a concrete version of webawesome (by rewriting the import to a CDN URL). This means that webawesome is also cached immutably and resolved out the browswer cache.

Page reload is _very_ fast.

### Production Workflow

The publish command
- runs fullLinkJS
- minifies the output
    - using terser for JS
    - uinng wasm-opt for wasm
- generates an index.html with the correct content-hashed references to the minified JS files
- copies the minified JS files into the publish destination directory
- Copies index.html into the publish destination directory
- copies asserts into the publish destination directory



