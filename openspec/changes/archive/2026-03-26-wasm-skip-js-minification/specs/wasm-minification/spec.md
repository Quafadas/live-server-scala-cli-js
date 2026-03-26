## MODIFIED Requirements

### Requirement: wasm-opt is invoked on WASM output during fullLinkJS
When `scalaJSExperimentalUseWebAssembly` is enabled, both `InMemoryHashScalaJSModule` and `FileBasedContentHashScalaJSModule` SHALL invoke `wasm-opt` on the emitted `.wasm` file as part of `fullLinkJS`, using the flags supplied by `wasmOptFlags`. The `minified` task SHALL NOT invoke `wasm-opt` a second time — the WASM binary produced by `fullLinkJS` is already optimised and is used as-is.

#### Scenario: WASM file is optimised during full link
- **WHEN** a module with `scalaJSExperimentalUseWebAssembly = true` runs `fullLinkJS`
- **THEN** the emitted `.wasm` file SHALL be smaller than or equal to the unoptimised `.wasm` produced by `fastLinkJS`

#### Scenario: fullLinkJS succeeds with default flags
- **WHEN** `wasmOptFlags` is not overridden (defaults to `Seq("-O2", "-all")`)
- **THEN** `wasm-opt` SHALL complete without error

#### Scenario: minified does not re-run wasm-opt
- **WHEN** a `FileBasedContentHashScalaJSModule` with `scalaJSExperimentalUseWebAssembly = true` runs `minified`
- **THEN** `wasm-opt` SHALL NOT be invoked
- **AND** the `.wasm` file in the `minified` output SHALL be the same binary as produced by `fullLinkJS`
