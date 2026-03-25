## Context

The plugin module provides Mill traits (`ContentHashScalaJSModule`, `FileBasedContentHashScalaJSModule`, `InMemoryHashScalaJSModule`) that users mix into their `ScalaJSModule` to get content-hashed JS output. The existing implementation already handles the core hashing workflow:

1. Intercept the Scala.js linker output (either via file-based temp directory or in-memory `OutputDirectory`)
2. Process JS files in topological (dependency-first) order
3. Compute SHA-256 content hashes, rename files to `<base>.<hash>.js`
4. Rewrite cross-module import references and `sourceMappingURL` comments
5. Return an updated `Report` with hashed filenames

Two strategies exist: **file-based** (writes linker output to a temp directory, then post-processes) and **in-memory** (uses a custom `OutputDirectory` that holds content in `ByteBuffer`s). The file-based approach is simpler and fully implemented. The in-memory approach is partially implemented (WASM hashing works, JS hashing is `???`).

## Goals / Non-Goals

**Goals:**
- Content-hashed JS filenames (`<base>.<hash>.js`) from `fastLinkJS` and `fullLinkJS`
- Replace "-" with "_" in generated filenames for terser compatibility
- Rewritten cross-module references so hashed output is self-consistent
- Source map filenames updated to match their JS counterparts
- Correct hash cascading: if a dependency's content changes, importers' hashes change too
- A `minified` task that runs terser on `fullLinkJS` output

**Non-Goals:**
- HTML generation or script tag rewriting (handled by sjsls server routes)
- CDN upload or deployment automation
- CSS/asset hashing (JS files only)
- Changing the Scala.js linker itself

## Decisions

### 1. Linker-wrapper approach (intercept `linkJs`)

**Decision**: Override `private[scalajslib] linkJs` to redirect linker output to a temp directory, then post-process.

**Rationale**: This is the only extension point that lets us rename files before Mill records the task output. If we post-processed after `fastLinkJS` completes, Mill's caching would see the original filenames and the hashed output would conflict with incremental builds.

**Alternative considered**: A separate `hashedFastLinkJS` task — rejected because it duplicates linking work and users would need to change all downstream references.

### 2. Package placement in `mill.scalajslib`

**Decision**: The trait lives in package `mill.scalajslib` (vendored in our source tree).

**Rationale**: `linkJs` and `ScalaJSWorker` are `private[scalajslib]`. Placing our trait in the same package gives access without forking Mill. This is a deliberate trade-off: we depend on Mill's internal API stability.

**Alternative considered**: Forking Mill or requesting an upstream API change — too heavy for this use case.

### 3. SHA-256 with 8-byte (16 hex char) prefix

**Decision**: Use the first 8 bytes of SHA-256 as the content hash.

**Rationale**: 16 hex characters provide ~64 bits of collision resistance — more than sufficient for cache busting. Shorter hashes keep filenames readable. SHA-256 is available in the JDK standard library with no external dependencies.

### 4. Topological processing order

**Decision**: Sort JS files by their import dependency graph before hashing.

**Rationale**: When file A imports file B, A's content includes B's hashed filename. Processing B first ensures its hash is known when A is rewritten. This makes hash cascading correct — a content change in B propagates to A's hash.

### 5. Filename sanitisation: "-" → "_"

**Decision**: Replace hyphens with underscores in generated filenames.

**Rationale**: Terser has issues parsing external source maps when filenames contain hyphens. Since Scala.js module IDs can produce hyphenated names, we normalise before hashing to avoid downstream minification failures.

### 6. File-based strategy as the primary implementation

**Decision**: `FileBasedContentHashScalaJSModule` is the main user-facing trait. `InMemoryHashScalaJSModule` is experimental.

**Rationale**: The file-based approach is simpler, fully tested, and works with all Mill caching semantics. The in-memory approach avoids disk I/O but requires more complex plumbing with the custom `OutputDirectory` and is not yet feature-complete for JS hashing.

## Risks / Trade-offs

- **Mill internal API breakage** → Pin to tested Mill versions; integration tests catch regressions early.
- **Hash instability across Scala.js versions** → Hashes depend on linker output content, which may change between Scala.js releases. This is acceptable — cache busting is the goal, not cross-version stability.
- **Terser availability** → The `minified` task shells out to `terser` via `os.proc`. If terser is not installed, the task fails with a clear error. This is documented as a prerequisite.
- **Large module splits** → With `SmallModulesFor`, Scala.js can produce hundreds of JS files. Topological sort and string replacement scale linearly but could be slow for very large splits. No mitigation needed yet — profile if it becomes an issue.
