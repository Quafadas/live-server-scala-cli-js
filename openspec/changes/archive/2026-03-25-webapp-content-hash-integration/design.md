## Context

`ScalaJsRefreshModule` generates `index.html` with a hardcoded `<script src="/main.js">` tag and serves linked JS from `fastLinkJS().dest.path`. When `FileBasedContentHashScalaJSModule` is mixed in (via `ScalaJsWebAppModule`), `fastLinkJS()` returns a `Report` whose `publicModules` contain hashed filenames like `main.a1b2c3d4.js` — but the HTML still references `/main.js`, so the app never loads.

The `Report` API provides `publicModules: Seq[Report.Module]` where each module has `jsFileName: String`.

## Goals / Non-Goals

**Goals:**
- `ScalaJsWebAppModule` generates HTML `<script>` tags that reference the actual hashed JS filenames from the linker report.
- `siteGen` and `siteGenFull` copy linked JS output into the site destination directory so hashed files are served.
- `ScalaJsRefreshModule` (without content hashing) continues to work unchanged with `/main.js`.

**Non-Goals:**
- Changing `ScalaJsRefreshModule`'s existing behaviour or its `indexHtmlBody`.
- Supporting multiple entry points (only the public modules from the report need script tags).
- Source map serving configuration.

## Decisions

**Override `indexHtmlBody` and `siteGen`/`siteGenFull` in `ScalaJsWebAppModule` rather than modifying `ScalaJsRefreshModule`.**
`ScalaJsRefreshModule` works correctly on its own (no hashing, `/main.js` is the real filename). The content-hash-aware behaviour only makes sense when `FileBasedContentHashScalaJSModule` is involved, so overrides belong in `ScalaJsWebAppModule`. This avoids adding content-hash-awareness to the base refresh module.

**Derive script tags from `fastLinkJS().publicModules` in `siteGen`.**
The `Report.publicModules` list already contains the hashed filenames after linking. `siteGen` already calls `fastLinkJS()`, so we can read `publicModules` from the report at that point. Rather than calling `fastLinkJS()` twice (once in `indexHtmlBody` and once in `siteGen`), we generate/overwrite `index.html` inside `siteGen` where we already have the report, using the hashed filenames.

**Copy linked JS files into the site destination.**
Currently `siteGen` records `fastLinkJS().dest.path` but only passes the path string to `lcs` as `outDir`. The live server config already knows how to serve from `outDir`. However, for `siteGenFull` (static site generation), we need the JS files colocated with `index.html`. Copy all files from the link destination into `Task.dest`.

## Risks / Trade-offs

- [Risk] `indexHtmlBody` becomes dependent on linker output order → The public modules list is stable per the Scala.js linker contract. The `moduleID` "main" is the conventional entry point.
- [Risk] Double-calling `fastLinkJS()` if both `indexHtmlBody` and `siteGen` invoke it → Mitigated by generating the final `index.html` inside `siteGen` where the report is already available, rather than in a separate `indexHtml` task.
