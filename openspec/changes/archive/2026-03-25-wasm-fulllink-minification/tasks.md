## 1. InMemoryHashScalaJSModule — wire wasm-opt into fullLinkJS

- [x] 1.1 Add a `fullLinkJS` override to `InMemoryHashScalaJSModule` that calls `super.fullLinkJS()` and then, when `scalaJSExperimentalUseWebAssembly()` is true, reads the `.wasm` bytes from `inMemoryOutputDirectory`, writes them to a temp file, invokes `wasm-opt` with `wasmOptFlags()`, reads the optimised bytes back, and stores them in `inMemoryOutputDirectory`
- [x] 1.2 Ensure `processWasm` (content-hashing of the WASM file) is called _after_ the `wasm-opt` step so the hash reflects the optimised binary
- [x] 1.3 Confirm `fastLinkJS` does NOT invoke `wasm-opt` (WASM optimisation is production-only)

## 2. FileBasedContentHashScalaJSModule — add wasmOptFlags and wire wasm-opt

- [x] 2.1 Add `def wasmOptFlags: Task[Seq[String]] = Task(Seq("-O2", "-all"))` to `FileBasedContentHashScalaJSModule`
- [x] 2.2 In the `fullLinkJS` override, after `super.fullLinkJS()` produces WASM output, detect any `.wasm` files in the output directory and invoke `wasm-opt` on each with `wasmOptFlags()`, writing the optimised bytes in-place before `applyContentHash` runs
- [x] 2.3 Confirm `fastLinkJS` does NOT invoke `wasm-opt`

## 3. Tests

- [x] 3.1 Add a utest integration test in `plugin/integration/src/wasm.test.scala` for `InMemoryHashScalaJSModule`: run `fullLinkJS` on the existing WASM integration fixture and assert the `.wasm` output file is smaller than the one produced by `fastLinkJS`
- [x] 3.2 Add a utest integration test for `FileBasedContentHashScalaJSModule`: same fixture, same size-reduction assertion
- [x] 3.3 Verify that the content hash in the `fullLinkJS` output `.wasm` filename differs from whatever hash would appear without optimisation (i.e. the optimised binary produces a different hash)
- [x] 3.4 Run `./mill plugin.integration` and confirm all WASM tests pass

## 4. Documentation

- [x] 4.1 Update the module-level scaladoc on both traits to mention the `wasmOptFlags` task and note that `wasm-opt` must be on `$PATH` for WASM full-link minification to work
