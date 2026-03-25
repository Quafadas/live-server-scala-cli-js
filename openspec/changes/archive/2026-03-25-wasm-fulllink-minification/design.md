## Context

Scala.js can emit WebAssembly output via `scalaJSExperimentalUseWebAssembly`. The two content-hash plugin traits (`InMemoryHashScalaJSModule` and `FileBasedContentHashScalaJSModule`) already handle WASM output in their `fastLinkJS` paths (rename / copy the `.wasm` file alongside hashed JS), but neither runs any binary optimisation on it before or after hashing.

`InMemoryHashScalaJSModule` already has an unused `wasmOptFlags: Task[Seq[String]] = Task(Seq("-O5", "-all"))` defined. `FileBasedContentHashScalaJSModule` has no equivalent.

The `wasm-opt` CLI (from [Binaryen](https://github.com/WebAssembly/binaryen)) is the standard tool for WASM binary optimisation. It must receive the `-all` flag when processing Scala.js WASM output, because Scala.js enables WASM features (e.g. GC, exception handling) that `wasm-opt` excludes from optimisation unless explicitly told to allow all features.

Both traits currently have a `fullLinkJS` override (or inherit from `ScalaJSConfigModule`) where minification belongs. The `minified` task in `FileBasedContentHashScalaJSModule` also applies terser to JS; WASM minification belongs in `fullLinkJS` rather than `minified`, because WASM mini- fication is a binary transform and is independent of JS terser minification.

## Goals / Non-Goals

**Goals:**
- Wire `wasmOptFlags` into `InMemoryHashScalaJSModule.fullLinkJS` so `wasm-opt` is invoked when `scalaJSExperimentalUseWebAssembly` is true.
- Add `wasmOptFlags` to `FileBasedContentHashScalaJSModule` and invoke `wasm-opt` in its `fullLinkJS` override when WASM output is detected.
- Hash the WASM file _after_ optimisation so the content hash reflects the final binary.
- Provide a utest integration test that builds a minimal WASM Scala.js project through both traits and asserts the `.wasm` file is smaller after optimisation.

**Non-Goals:**
- Optimising `.wasm` files on `fastLinkJS` (fast link is for development; optimisation cost is not justified there).
- Bundling `wasm-opt` itself — it must already be present on `$PATH` (same approach as `terser`).
- Supporting custom `wasm-opt` binary paths (out of scope; users can set `$PATH`).

## Decisions

### Where to invoke `wasm-opt`

**Decision**: Call `wasm-opt` inside the `fullLinkJS` Task override, immediately after the super call produces the linked output, and before content-hashing.

**Alternatives considered**:
- A separate `wasmOptimised` task (analogous to `minified`) — rejected because it would require users to wire an extra task into their build and the WASM optimisation step has no meaningful intermediate artifact to expose; it is an inherent part of a production full link.
- Running inside `processWasm` — rejected for `FileBasedContentHashScalaJSModule` because that helper does not exist there; keeping the call site symmetric in `fullLinkJS` is simpler.

### In-memory vs file-based: shared helper or parallel implementations

**Decision**: Keep the two traits independent. Do not extract a shared helper for `wasm-opt` invocation.

**Rationale**: The in-memory trait works with `ByteBuffer` content in a `MemOutputDirectory`; the file-based trait works with `os.Path` files. A shared helper would need to abstract over both, adding complexity for a single `os.proc` call. Two small, self-contained implementations are easier to read and test.

### When to hash the WASM file

**Decision**: Hash the WASM binary _after_ `wasm-opt` so the stored hash reflects the optimised artifact.

This is consistent with how the `minified` task in `FileBasedContentHashScalaJSModule` strips the pre-hash before re-hashing after terser.

### `wasm-opt` flag defaults

**Decision**: Default flags are `Seq("-O2", "-all")`.
- `-O2` is a solid general-purpose optimisation level supported across Binaryen versions.
- `-all` enables all WASM features, which is required for Scala.js WASM output.

Users can override `wasmOptFlags` in their module to reduce optimisation level or add extra flags.

## Risks / Trade-offs

- **`wasm-opt` not on PATH** → `os.proc` will throw at runtime with a clear error. Mitigation: document the prerequisite in the README / spec; the same precedent exists for `terser`.
- **`wasm-opt` version skew** → Different Binaryen versions may produce different output for the same input, causing spurious cache misses. Mitigation: document the recommended version; no action required in code.
- **`-all` is not future-proof** → A future WASM feature unsupported by the installed `wasm-opt` version could cause a failure. Mitigation: `wasmOptFlags` is overridable; users can pin to a specific feature set.
- **In-memory path reads ByteBuffer twice** → After `wasm-opt` writes optimised bytes back to `MemOutputDirectory`, the existing `processWasm` rename logic re-reads them for hashing. This is acceptable; the extra copy is negligible compared to the optimisation time.

## Open Questions

- Should `fullLinkJS` in `InMemoryHashScalaJSModule` also call `wasm-opt` for the JS-only path (i.e. when WASM is disabled)? → No, `wasm-opt` is irrelevant there; the JS-only path already hashes JS files.
- Should the integration test assert file size reduction, or just that `wasm-opt` ran without error? → Assert size reduction (optimised < unoptimised) as a meaningful correctness check.
