## Context

Mill's `ScalaJSModule` exposes `scalaJSMinify: T[Boolean]` (defaults `true`). This flag is passed directly to the Scala.js linker's `minify` parameter, enabling linker-level size reduction during `fullLinkJS`. It is unrelated to terser.

The plugin provides two module traits with different output strategies:

- **`FileBasedContentHashScalaJSModule`** — always writes to disk. Has a `minified` task that post-processes `fullLinkJS` output through `terser`. When `scalaJSExperimentalUseWebAssembly = true`, the linker emits JS loader files alongside the `.wasm` binary; terser can break these loaders. Currently has no special WASM handling in `minified`.
- **`InMemoryHashScalaJSModule`** — keeps linker output in an in-memory directory. `fastLinkJS` hashes in-memory and writes to `Task.dest`. `fullLinkJS` does the same (plus `wasm-opt` in WASM mode). Has **no `minified` task** — so JS files are never terser-minified in production builds.

The desired end-state:
- Both module traits should terser-minify JS output for non-WASM `fullLinkJS` builds (controlled by `scalaJSMinify`).
- Neither should run terser in WASM mode (where `wasm-opt` is the appropriate tool).
- `fastLinkJS` output stays in-memory for `InMemoryHashScalaJSModule`; disk output is only for `fullLinkJS`.

## Goals / Non-Goals

**Goals:**
- `FileBasedContentHashScalaJSModule.minified`: when `scalaJSExperimentalUseWebAssembly()` is `true`, copy all files unchanged (skip terser). Non-WASM path unchanged.
- `InMemoryHashScalaJSModule.fullLinkJS`: when `scalaJSExperimentalUseWebAssembly()` is `false` and `scalaJSMinify()` is `true`, run terser on each JS file in-memory before hashing and writing to `Task.dest`.
- `InMemoryHashScalaJSModule.fastLinkJS`: no change — stays in-memory, no terser.
- `InMemoryHashScalaJSModule.fullLinkJS` WASM path: no change — `wasm-opt` already runs; do not add terser.

**Non-Goals:**
- Changing how `scalaJSMinify` interacts with the Scala.js linker itself.
- Running terser during `fastLinkJS` on either module.
- Running terser on WASM loader JS in either module.

## Decisions

### Gate terser on `scalaJSMinify` in `InMemoryHashScalaJSModule`

`scalaJSMinify` is the user-facing flag that already controls linker-level minification. Reusing it for the terser gate is the least-surprise approach: one flag that means "apply all production minification". This avoids introducing a second flag.

### Terser invocation for `InMemoryHashScalaJSModule`

For each JS file in the in-memory directory, write it to a temp file, invoke `terser`, read the result back into memory, then proceed with the existing hash-and-write-to-disk flow. Source maps are updated as in `FileBasedContentHashScalaJSModule.minified`. This reuses the existing `terserConfig` task.

### Detect WASM mode via `scalaJSExperimentalUseWebAssembly`

Both module traits check `scalaJSExperimentalUseWebAssembly()` directly — the canonical flag already used by `fullLinkJS` — rather than inspecting output file extensions.

## Risks / Trade-offs

- [Risk] `InMemoryHashScalaJSModule.fullLinkJS` now writes to disk (it already did) and invokes terser — build time increases for production JS-only builds. This is expected and acceptable; users can set `scalaJSMinify = false` to skip it.
- [Risk] `scalaJSMinify` could be confused with the `minified` task on `FileBasedContentHashScalaJSModule` — they are related but distinct: `scalaJSMinify` gates both the Scala.js linker pass and the terser pass; `minified` is the `FileBasedContentHashScalaJSModule`-specific task that wraps terser. Doc-comments on both should clarify this.

## Migration Plan

No migration needed. Non-WASM production builds gain terser minification in `InMemoryHashScalaJSModule` (previously absent). WASM builds that previously failed under terser in `FileBasedContentHashScalaJSModule.minified` now succeed. Users can opt out by setting `override def scalaJSMinify = Task(false)`.
