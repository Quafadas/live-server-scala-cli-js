## Why

`ScalaJsWebAppModule` has a `publish` task that copies the minified/fully-linked JS output (`minified()`) into `Task.dest`, but the `index.html` is generated from `indexHtml()` which derives script `src` attributes from `fastLinkJS()`. Since `fastLinkJS` and `fullLinkJS`/`minified` produce different content-hashed filenames, the published `index.html` references non-existent JS files — the site directory is broken as a standalone deployment artifact.

## What Changes

- The `publish` task on `ScalaJsWebAppModule` SHALL be replaced with a correct implementation that generates `index.html` whose `<script>` tags reference the public modules from `minified()` (not `fastLinkJS()`).
- All JS and source-map files produced by `minified()` SHALL be copied into `Task.dest`.
- The assets directory SHALL be copied into `Task.dest` when it exists (consistent with existing `siteGen` behaviour).
- The resulting `Task.dest` SHALL be a self-contained site directory suitable for direct deployment (no external references to other Mill output directories).
- The refresh/SSE script SHALL be omitted from the published `index.html`, since live-reload infrastructure is not present in a production deployment.

## Capabilities

### New Capabilities

- `webapp-publish-task`: A corrected `publish` task on `ScalaJsWebAppModule` that produces a self-contained deployment-ready site directory containing `index.html` (with hashed script references), all minified/content-hashed JS outputs, and any static assets.

### Modified Capabilities

- `plugin`: The existing `plugin` spec requirement for `ScalaJsWebAppModule` must be extended to cover the `publish` task behaviour — specifically that it uses the minified report for script references and produces a self-contained site.

## Impact

- `plugin/src/refresh_plugin.scala`: `publish` task body in `ScalaJsWebAppModule` is rewritten.
- No changes to `siteGen`, `siteGenFull`, `fastLinkJS`, `fullLinkJS`, or `minified` tasks.
- No new dependencies.
- No breaking changes to the public Mill task API (task name stays `publish`).
