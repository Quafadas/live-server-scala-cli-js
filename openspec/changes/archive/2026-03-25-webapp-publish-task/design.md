## Context

`ScalaJsWebAppModule` in `plugin/src/refresh_plugin.scala` extends `FileBasedContentHashScalaJSModule` and `ScalaJsRefreshModule`. It currently exposes three site-generation tasks:

- `siteGen` — fast-linked JS + index.html (for dev proxy use)
- `siteGenFull` — full-linked JS + index.html (no minification)
- `publish` — minified, fully-linked JS + index.html (intended for deployment)

The `publish` task calls `minified()` for the JS output but reuses `indexHtml()`, which is defined on `ScalaJsRefreshModule` and internally calls `fastLinkJS()`. Since `fastLinkJS` and `minified` (which wraps `fullLinkJS`) produce different content-hashed filenames, the `index.html` will contain `<script src="/main.<fast-hash>.js">` while `Task.dest` only contains `main.<min-hash>.js` — a broken site.

Additionally, `indexHtmlBody` injects the SSE live-reload script (`/refresh/v1/sse` listener), which is meaningless and potentially harmful in a production deployment.

## Goals / Non-Goals

**Goals:**
- `publish` produces an `index.html` whose `<script>` `src` attributes reference the public modules from `minified()`.
- All JS (and source-map) files from `minified()` are copied into `Task.dest`.
- Assets are copied when `assetsDir` exists.
- The live-reload SSE script is absent from the published HTML.
- `Task.dest` is self-contained: deployable by uploading the directory as-is.

**Non-Goals:**
- Changing `siteGen` or `siteGenFull` in any way.
- Adding new Mill tasks beyond `publish`.
- Supporting WASM as a separate code path in `publish` — `minified()` already handles WASM-opt internally; `publish` consumes its report uniformly.
- Changing `indexHtml()` or `indexHtmlHead()`/`indexHtmlBody()` — they remain unchanged for dev use.

## Decisions

### Generate inline HTML rather than reusing `indexHtml()`

**Decision:** Build the `<head>` and `<body>` content directly inside the `publish` task using `indexHtmlHead()` (reused as-is) and a locally constructed body that iterates `minified().publicModules`.

**Rationale:** `indexHtml()` is a cached Mill `Task` that depends on `fastLinkJS()`. If we reused it, Mill would execute `fastLinkJS` even for a `publish` invocation, wasting time and introducing a second set of differently-hashed files. By constructing the HTML inline from the `minified()` report — which is already a `Task` dependency of `publish` — we avoid pulling in the fast-link task graph entirely. The head HTML (`<meta>`, `<title>`, external stylesheets) is already parameterised through `indexHtmlHead()`, so we call that and compose the body ourselves.

**Alternative considered:** Override `indexHtmlBody` to accept an arbitrary `Report` parameter. Rejected because Mill tasks cannot take runtime parameters; we would need a separate task, which is essentially the same as inline construction.

### Omit the SSE refresh script in published HTML

**Decision:** The `publish` task body does not include `refreshScript`.

**Rationale:** The refresh script opens a persistent SSE connection to `/refresh/v1/sse`. That endpoint does not exist in a static deployment; the connection would error repeatedly in a production browser. The script also adds a non-trivial inline `<script>` block. Omission keeps the published HTML clean and avoids confusing runtime errors.

### Copy strategy: walk `minified().dest.path`

**Decision:** Copy all files from `minified().dest.path` into `Task.dest` using `os.copy` with `replaceExisting = true`, mirroring the pattern already used in `siteGen`.

**Rationale:** `minified()` returns a `Report` whose `dest.path` contains all JS and `.map` files needed. Walking and copying them is the same approach used by `siteGen`/`siteGenFull`, keeping the implementation consistent.

## Risks / Trade-offs

- [Risk] `minified()` depends on `terser` and `wasm-opt` being present on `$PATH`. If either is missing `publish` will fail. → Mitigation: this is already the case for `minified()` in isolation; `publish` inherits that constraint without making it worse. Documentation should note the dependency.
- [Risk] `indexHtmlHead()` indirectly depends on `fastLinkJS()` if it is ever extended by a user to include script tags. → Mitigation: the current implementation of `indexHtmlHead()` only emits `<meta>` and `<link>` tags; it has no dependency on a linker report. Document that `publish` calls `indexHtmlHead()`.

## Migration Plan

Drop-in replacement: the `publish` task signature and name are unchanged. Any CI pipeline calling `mill app.publish` will get a correct, self-contained output directory instead of the currently broken one. No rollback concern — this is a bug fix.
