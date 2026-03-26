## Why

The `InMemoryHashScalaJSModule` and `FileBasedContentHashScalaJSModule` traits support Scala.js WASM output (`scalaJSExperimentalUseWebAssembly`), but neither applies `wasm-opt` minification during a full link. Unminified WASM binaries can be significantly larger than necessary, hurting load times for production builds.

## What Changes

- Both `InMemoryHashScalaJSModule` and `FileBasedContentHashScalaJSModule` gain the ability to run `wasm-opt` on the emitted `.wasm` file as part of a full link.
- The optimisation flags default to `Seq("-O2", "-all")`. The `-all` flag is mandatory — omitting it causes `wasm-opt` to fail because Scala.js emits WASM features that are not enabled in the default feature set.
- `InMemoryHashScalaJSModule` already declares `wasmOptFlags` but never invokes `wasm-opt`; this change wires it up in `fullLinkJS`.
- `FileBasedContentHashScalaJSModule` gains a matching `wasmOptFlags` task and WASM minification in its `fullLinkJS` (and `minified`) tasks.

## Capabilities

### New Capabilities

- `wasm-minification`: Run `wasm-opt` on WASM output produced by a full link in both content-hash module traits, with a user-overridable `wasmOptFlags` task that defaults to `["-O5", "-all"]`.

### Modified Capabilities

- `content-hashing`: The full-link path for both module traits must run WASM minification before content-hashing the WASM file, so the stored hash reflects the optimised binary.

## Impact

- `plugin/src/InMemoryHashScalaJSModule.scala` — wire `wasmOptFlags` into the `fullLinkJS` override.
- `plugin/src/FileBasedContentHashScalaJSModule.scala` — add `wasmOptFlags` task and invoke `wasm-opt` in `fullLinkJS` / `minified`.
- New tests in `plugin/integration/src/` (or `plugin/test/src/`) covering wasm-opt invocation on a WASM full link.
- No API breakage; `wasmOptFlags` is an overridable `Task[Seq[String]]`.
