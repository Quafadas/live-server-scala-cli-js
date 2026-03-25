## MODIFIED Requirements

### Requirement: Minified task
The plugin SHALL provide a `minified` task that runs terser on the `fullLinkJS` output to produce minified JS files with chained source maps and post-minification content hashes.

#### Scenario: Minified output has chained source maps
- **WHEN** the `minified` task is invoked
- **THEN** terser SHALL be called with `--source-map "content=<input>.map"` for each JS file
- **AND** each minified JS file SHALL have a corresponding `.map` file that chains through the Scala.js linker's source map

#### Scenario: Minified output has post-minification content hashes
- **WHEN** the `minified` task completes
- **THEN** each minified JS file SHALL be named `<base>.<hash>.js` where `<hash>` is the SHA-256 content hash of the minified bytes
- **AND** the hash SHALL NOT be the pre-minification hash from `fullLinkJS`

#### Scenario: Pre-minification hash is stripped before re-hashing
- **WHEN** `fullLinkJS` produces `main.a1b2c3d4.js`
- **AND** the `minified` task processes it
- **THEN** the output SHALL be named `main.<postHash>.js` (not `main.a1b2c3d4.<postHash>.js`)

#### Scenario: Cross-module imports are rewritten in minified output
- **GIVEN** minified `main.js` imports `chunk.js`
- **WHEN** post-minification hashing is applied
- **THEN** the import reference SHALL be rewritten to `chunk.<postHash>.js`

#### Scenario: sourceMappingURL is correct in minified output
- **WHEN** post-minification hashing is applied to a minified JS file
- **THEN** the `sourceMappingURL` comment SHALL reference `<base>.<postHash>.js.map`
- **AND** the source map file SHALL be renamed to match

#### Scenario: Terser unavailable
- **WHEN** the `minified` task is invoked
- **AND** terser is not available on the system path
- **THEN** the task SHALL fail with a clear error message
