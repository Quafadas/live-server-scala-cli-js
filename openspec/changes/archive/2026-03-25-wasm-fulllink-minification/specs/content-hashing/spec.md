# content-hashing Delta Specification

## MODIFIED Requirements

### Requirement: Content hash WASM output filenames after optimisation
When WASM output is produced by `fullLinkJS`, the content hash applied to `.wasm` files SHALL be computed on the _optimised_ binary (after `wasm-opt` has run), not the raw linker output. This ensures the stored hash reflects the final artifact that will be served to clients.

#### Scenario: WASM hash reflects optimised binary
- **GIVEN** a module with `scalaJSExperimentalUseWebAssembly = true` runs `fullLinkJS`
- **WHEN** content hashing is applied
- **THEN** the `.wasm` file in the output directory SHALL be named `<base>.<hash>.wasm` where `<hash>` is derived from the `wasm-opt`-optimised bytes
- **AND** the hash SHALL differ from what would be computed on the unoptimised binary
