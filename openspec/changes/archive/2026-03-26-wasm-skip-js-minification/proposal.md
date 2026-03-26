## Why

When Scala.js emits WASM output, the linker also emits JS loader files (e.g. `__loader.js`) that bootstrap the WASM module. The current `minified` task passes every `.js` file in the `fullLinkJS` output through `terser`, including these loader files. Terser does not understand the specialised WASM-bootstrap patterns and can produce broken output; even when it does not break, minifying a tiny loader file is unnecessary overhead. Only the WASM binary itself benefits meaningfully from optimisation (which is already handled by `wasm-opt`).

## What Changes

- The `minified` task in `FileBasedContentHashScalaJSModule` (and the equivalent path in `InMemoryHashScalaJSModule`) will skip JS files when the module is in WASM mode.
- When `scalaJSExperimentalUseWebAssembly` is `true`, `minified` will copy JS loader files unchanged and only pass `.wasm` files through the existing `wasm-opt` pipeline (which already runs in `fullLinkJS`).
- No change to the non-WASM code path.

## Capabilities

### New Capabilities

- `wasm-js-passthrough`: When emitting WASM, JS loader files emitted alongside the `.wasm` binary are copied as-is by `minified`, bypassing terser.

### Modified Capabilities

- `wasm-minification`: The existing requirement that `wasm-opt` is the only minification tool applied to WASM builds is made explicit and enforced in `minified` as well as `fullLinkJS`.

## Impact

- `plugin/src/FileBasedContentHashScalaJSModule.scala` — `minified` task gains a WASM branch
- `plugin/integration/src/wasm.test.scala` — new integration test scenario covering `minified` in WASM mode
- No API or published interface changes; `wasmOptFlags` and `terserConfig` remain unchanged
