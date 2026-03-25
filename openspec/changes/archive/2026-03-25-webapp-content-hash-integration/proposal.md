## Why

`ScalaJsWebAppModule` mixes `FileBasedContentHashScalaJSModule` into `ScalaJsRefreshModule`, but the refresh module's `indexHtmlBody` hardcodes `<script src="/main.js">`. When content hashing is active, the linker output is named `main.<hash>.js`, so the HTML never loads the actual JS file. The two traits are combined but don't actually work together — the HTML generation must discover the hashed entry-point filename from the linker report.

## What Changes

- `ScalaJsWebAppModule` becomes a proper trait (not just a mixin alias) that overrides `indexHtmlBody` to resolve the hashed entry-point JS filename from `fastLinkJS()` output.
- The generated `<script>` tag will reference the actual hashed filename (e.g. `/main.a1b2c3d4.js`) instead of the static `/main.js`.
- `siteGen` and `siteGenFull` will copy the linked JS output files into the site destination so the hashed JS is served alongside `index.html`.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `plugin`: The `ScalaJsWebAppModule` trait SHALL generate HTML that references the content-hashed entry-point JS filename, and `siteGen`/`siteGenFull` SHALL include all linked JS output in the served directory.

## Impact

- **Code**: `plugin/src/refresh_plugin.scala` — `ScalaJsWebAppModule` trait, `siteGen`, `siteGenFull`, `indexHtmlBody`.
- **Behaviour**: Projects using `ScalaJsWebAppModule` will get correct `<script>` tags pointing to hashed JS filenames. **BREAKING** for anyone who relied on the `/main.js` script path (unlikely since it didn't work with hashing).
- **No new dependencies**.
