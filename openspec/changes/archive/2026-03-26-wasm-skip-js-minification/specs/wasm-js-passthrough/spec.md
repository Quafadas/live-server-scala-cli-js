## ADDED Requirements

### Requirement: FileBasedContentHashScalaJSModule does not run terser on WASM builds
When `scalaJSExperimentalUseWebAssembly` is `true`, the `minified` task in `FileBasedContentHashScalaJSModule` SHALL copy all files unchanged to the output directory instead of passing JS files through `terser`.

#### Scenario: JS loader files are copied unchanged in WASM mode
- **WHEN** a `FileBasedContentHashScalaJSModule` with `scalaJSExperimentalUseWebAssembly = true` runs `minified`
- **THEN** all `.js` files in the output SHALL be the same size as the corresponding files in the `fullLinkJS` output

#### Scenario: minified succeeds in WASM mode without terser
- **WHEN** a `FileBasedContentHashScalaJSModule` with `scalaJSExperimentalUseWebAssembly = true` runs `minified`
- **THEN** the task SHALL complete successfully without invoking `terser`

#### Scenario: WASM file is included in minified output
- **WHEN** a `FileBasedContentHashScalaJSModule` with `scalaJSExperimentalUseWebAssembly = true` runs `minified`
- **THEN** the output directory SHALL contain a content-hashed `.wasm` file copied from the `fullLinkJS` output

### Requirement: InMemoryHashScalaJSModule runs terser during fullLinkJS for non-WASM builds
When `scalaJSExperimentalUseWebAssembly` is `false` and `scalaJSMinify` is `true`, `InMemoryHashScalaJSModule.fullLinkJS` SHALL invoke `terser` on each JS file before hashing and writing to `Task.dest`.

#### Scenario: fullLinkJS JS output is smaller than fastLinkJS when scalaJSMinify is true
- **WHEN** an `InMemoryHashScalaJSModule` with `scalaJSMinify = true` runs `fullLinkJS`
- **THEN** the total size of `.js` files in `Task.dest` SHALL be smaller than the total size of `.js` files produced by `fastLinkJS`

#### Scenario: fullLinkJS skips terser when scalaJSMinify is false
- **WHEN** an `InMemoryHashScalaJSModule` with `scalaJSMinify = false` runs `fullLinkJS`
- **THEN** `terser` SHALL NOT be invoked

#### Scenario: fastLinkJS does not run terser
- **WHEN** an `InMemoryHashScalaJSModule` runs `fastLinkJS`
- **THEN** `terser` SHALL NOT be invoked and in-memory output SHALL be present

#### Scenario: fullLinkJS WASM path does not run terser
- **WHEN** an `InMemoryHashScalaJSModule` with `scalaJSExperimentalUseWebAssembly = true` runs `fullLinkJS`
- **THEN** `terser` SHALL NOT be invoked
