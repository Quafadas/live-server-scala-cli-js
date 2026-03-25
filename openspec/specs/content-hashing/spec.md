# content-hashing Specification

## Purpose

Content hashing renames Scala.js linker output files to include a SHA-256 content hash in their filenames (e.g. `main.a1b2c3d4.js`). This enables immutable browser caching while guaranteeing automatic cache busting whenever file content changes.

### Requirement: Content hash JS output filenames
The plugin SHALL rename all JS files produced by `fastLinkJS` and `fullLinkJS` to include a SHA-256 content hash in the form `<base>.<hash>.js`, where `<hash>` is the first 8 bytes (16 hex characters) of the SHA-256 digest of the file's final content.

#### SCENARIO:
- GIVEN the Scala.js linker produces a single `main.js` file
- WHEN content hashing is applied
- THEN the output directory SHALL contain a file named `main.<hash>.js` where `<hash>` is the SHA-256 content hash
- AND the original `main.js` SHALL NOT exist in the output directory

#### SCENARIO:
- GIVEN the Scala.js linker produces multiple JS files (e.g. via `SmallModulesFor` split)
- WHEN content hashing is applied
- THEN every JS file in the output directory SHALL have a content hash in its filename
- AND no unhashed JS filenames SHALL remain in the output directory

### Requirement: Cross-module import references are rewritten
The plugin SHALL rewrite all cross-module import/export references within JS files to use the hashed filenames, so that the output is self-consistent.

#### SCENARIO:
- GIVEN `main.js` contains `import * as chunk from "./chunk.js"`
- WHEN content hashing is applied
- THEN the hashed `main.<hash>.js` SHALL contain the import rewritten to `"./chunk.<hash>.js"`

#### SCENARIO:
- GIVEN a JS file contains imports using either `"./dep.js"` or `'./dep.js'` syntax
- WHEN content hashing is applied
- THEN both forms SHALL be rewritten to reference the hashed filename

### Requirement: Source map references are updated
The plugin SHALL update `sourceMappingURL` comments in JS files and rename source map files to match their corresponding hashed JS filenames.

#### SCENARIO:
- GIVEN `main.js` contains `//# sourceMappingURL=main.js.map`
- WHEN content hashing is applied
- THEN the hashed output SHALL contain `//# sourceMappingURL=main.<hash>.js.map`
- AND the source map file SHALL be renamed to `main.<hash>.js.map`

### Requirement: Hash cascading on dependency changes
The plugin SHALL process files in topological (dependency-first) order so that a content change in a dependency cascades to the hash of all files that import it.

#### SCENARIO:
- GIVEN file `b.js` content changes
- AND file `a.js` imports `b.js`
- WHEN content hashing is applied
- THEN `b.<hash>.js` SHALL have a different hash than before the change
- AND `a.<hash>.js` SHALL also have a different hash (because its rewritten import content changed)

### Requirement: Filename sanitisation replaces hyphens with underscores
The plugin SHALL replace all "-" characters with "_" in the names of generated files, to avoid issues with terser's external source map handling.

#### SCENARIO:
- GIVEN the Scala.js linker produces a file named `my-module.js`
- WHEN content hashing is applied
- THEN the output file SHALL be named `my_module.<hash>.js` (hyphen replaced with underscore before hashing)
- AND no hashed output filename SHALL contain a hyphen

### Requirement: Report reflects hashed filenames
The plugin SHALL return an updated `Report` where `publicModules` entries reference the hashed filenames, so downstream consumers see the correct names.

#### SCENARIO:
- GIVEN `fastLinkJS` or `fullLinkJS` completes with content hashing active
- WHEN the returned `Report` is inspected
- THEN every `Report.Module` SHALL have its `jsFileName` set to the hashed filename
- AND `sourceMapName` (if present) SHALL reference the hashed source map filename

### Requirement: Minified task
The plugin SHALL provide a `minified` task that runs terser on the `fullLinkJS` output to produce minified JS files with chained source maps and post-minification content hashes.

#### SCENARIO:
- GIVEN the `minified` task is invoked
- WHEN terser is available on the system path
- THEN terser SHALL be run on each JS file from `fullLinkJS` output with `--source-map "content=<input>.map"` to chain source maps
- AND each minified JS file SHALL have a corresponding `.map` file that traces back to original Scala sources

#### SCENARIO:
- GIVEN the `minified` task completes
- WHEN the output directory is inspected
- THEN each minified JS file SHALL be named `<base>.<hash>.js` where `<hash>` is the SHA-256 content hash of the minified bytes
- AND the hash SHALL NOT be the pre-minification hash from `fullLinkJS`
- AND the pre-minification hash SHALL be stripped before applying the post-minification hash (e.g. `main.<preHash>.js` becomes `main.<postHash>.js`)

#### SCENARIO:
- GIVEN minified JS files contain cross-module imports
- WHEN post-minification hashing is applied
- THEN all import references SHALL be rewritten to use the post-minification hashed filenames
- AND `sourceMappingURL` comments SHALL reference `<base>.<postHash>.js.map`
- AND the source map file SHALL be renamed to match

#### SCENARIO:
- GIVEN the `minified` task is invoked
- WHEN terser is not available on the system path
- THEN the task SHALL fail with a clear error message
