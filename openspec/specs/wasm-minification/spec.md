# wasm-minification Specification

## Purpose

When Scala.js emits WASM output (`scalaJSExperimentalUseWebAssembly = true`), the production `fullLinkJS` build can invoke `wasm-opt` (Binaryen) to optimise the emitted `.wasm` binary, reducing its size before content hashing and serving.

### Requirement: wasm-opt is invoked on WASM output during fullLinkJS
When `scalaJSExperimentalUseWebAssembly` is enabled, both `InMemoryHashScalaJSModule` and `FileBasedContentHashScalaJSModule` SHALL invoke `wasm-opt` on the emitted `.wasm` file as part of `fullLinkJS`, using the flags supplied by `wasmOptFlags`.

#### Scenario: WASM file is optimised during full link
- **WHEN** a module with `scalaJSExperimentalUseWebAssembly = true` runs `fullLinkJS`
- **THEN** the emitted `.wasm` file SHALL be smaller than or equal to the unoptimised `.wasm` produced by `fastLinkJS`

#### Scenario: fullLinkJS succeeds with default flags
- **WHEN** `wasmOptFlags` is not overridden (defaults to `Seq("-O2", "-all")`)
- **THEN** `wasm-opt` SHALL complete without error

### Requirement: The -all flag MUST be included in wasmOptFlags
`wasm-opt` SHALL be invoked with a flag set that includes `-all`. Omitting `-all` causes `wasm-opt` to reject WASM features emitted by Scala.js, resulting in a build failure.

#### Scenario: Default flags include -all
- **WHEN** `wasmOptFlags` is evaluated at its default value
- **THEN** the returned sequence SHALL contain the string `"-all"`

#### Scenario: Missing -all causes failure
- **WHEN** `wasmOptFlags` is overridden to a sequence that does not include `"-all"`
- **THEN** `wasm-opt` SHALL fail with a non-zero exit code when processing Scala.js WASM output

### Requirement: wasmOptFlags is user-overridable
`wasmOptFlags` SHALL be a `Task[Seq[String]]` on both module traits so that users can override the optimisation level or add extra flags.

#### Scenario: User overrides optimisation level
- **WHEN** a user's module overrides `def wasmOptFlags = Task(Seq("-O2", "-all"))`
- **THEN** `fullLinkJS` SHALL invoke `wasm-opt` with those flags instead of the defaults

### Requirement: wasm-opt is only invoked on fullLinkJS, not fastLinkJS
`wasm-opt` SHALL NOT be invoked during `fastLinkJS`. Fast link is for development iteration speed; WASM optimisation is only appropriate for production builds.

#### Scenario: fastLinkJS does not invoke wasm-opt
- **WHEN** a module with `scalaJSExperimentalUseWebAssembly = true` runs `fastLinkJS`
- **THEN** `wasm-opt` SHALL NOT be called
- **AND** the `.wasm` file in the output SHALL be the raw unoptimised linker output
