## ADDED Requirements

### Requirement: Content hash JS output filenames
The plugin SHALL rename all JS files produced by `fastLinkJS` and `fullLinkJS` to include a SHA-256 content hash in the form `<base>.<hash>.js`, where `<hash>` is the first 8 bytes (16 hex characters) of the SHA-256 digest of the file's final content.

#### Scenario: Single JS file is content-hashed
- **WHEN** the Scala.js linker produces a single `main.js` file
- **THEN** the output directory SHALL contain a file named `main.<hash>.js` where `<hash>` is the SHA-256 content hash
- **AND** the original `main.js` SHALL NOT exist in the output directory

#### Scenario: Multiple JS files are content-hashed
- **WHEN** the Scala.js linker produces multiple JS files (e.g. via `SmallModulesFor` split)
- **THEN** every JS file in the output directory SHALL have a content hash in its filename
- **AND** no unhashed JS filenames SHALL remain in the output directory

### Requirement: Cross-module import references are rewritten
The plugin SHALL rewrite all cross-module import/export references within JS files to use the hashed filenames, so that the output is self-consistent.

#### Scenario: Imports reference hashed filenames
- **WHEN** `main.js` contains `import * as chunk from "./chunk.js"`
- **THEN** the hashed `main.<hash>.js` SHALL contain the import rewritten to `"./chunk.<hash>.js"`

#### Scenario: Both double-quoted and single-quoted imports are rewritten
- **WHEN** a JS file contains imports using either `"./dep.js"` or `'./dep.js'` syntax
- **THEN** both forms SHALL be rewritten to reference the hashed filename

### Requirement: Source map references are updated
The plugin SHALL update `sourceMappingURL` comments in JS files and rename source map files to match their corresponding hashed JS filenames.

#### Scenario: sourceMappingURL points to hashed map file
- **WHEN** `main.js` contains `//# sourceMappingURL=main.js.map`
- **THEN** the hashed output SHALL contain `//# sourceMappingURL=main.<hash>.js.map`
- **AND** the source map file SHALL be renamed to `main.<hash>.js.map`

### Requirement: Hash cascading on dependency changes
The plugin SHALL process files in topological (dependency-first) order so that a content change in a dependency cascades to the hash of all files that import it.

#### Scenario: Dependency content change cascades to importer hash
- **WHEN** file `b.js` content changes
- **AND** file `a.js` imports `b.js`
- **THEN** `b.<hash>.js` SHALL have a different hash
- **AND** `a.<hash>.js` SHALL also have a different hash (because its rewritten import content changed)

### Requirement: Filename sanitisation replaces hyphens with underscores
The plugin SHALL replace all "-" characters with "_" in the names of generated files, to avoid issues with terser's external source map handling.

#### Scenario: Hyphenated module names are sanitised
- **WHEN** the Scala.js linker produces a file named `my-module.js`
- **THEN** the output file SHALL be named `my_module.<hash>.js` (hyphen replaced with underscore before hashing)

### Requirement: Report reflects hashed filenames
The plugin SHALL return an updated `Report` where `publicModules` entries reference the hashed filenames, so downstream consumers see the correct names.

#### Scenario: Report publicModules use hashed names
- **WHEN** `fastLinkJS` or `fullLinkJS` completes
- **THEN** every `Report.Module` in the returned `Report` SHALL have its `jsFileName` set to the hashed filename
- **AND** `sourceMapName` (if present) SHALL reference the hashed source map filename

### Requirement: Minified task
The plugin SHALL provide a `minified` task that runs terser on the `fullLinkJS` output to produce minified JS files.

#### Scenario: Minified output is produced
- **WHEN** the `minified` task is invoked
- **THEN** terser SHALL be run on each JS file from `fullLinkJS` output
- **AND** the minified files SHALL be written to the task output directory

## MODIFIED Requirements

### Requirement: Refresh on change
The tool should be able to detect changes in the source code and refresh the application accordingly.

#### Scenario: Plugin module with content hashing
- **WHEN** a user mixes `FileBasedContentHashScalaJSModule` (or `ContentHashScalaJSModule`) into their `ScalaJSModule`
- **THEN** `fastLinkJS` and `fullLinkJS` SHALL produce content-hashed JS output transparently
- **AND** the refresh plugin's live reload SHALL continue to work with the hashed filenames
