## Context

The `FileBasedContentHashScalaJSModule` trait provides a `minified` task that runs terser on `fullLinkJS` output. Currently it:
- Iterates over hashed JS files from `fullLinkJS` (e.g. `main.a1b2c3d4.js`)
- Runs terser without `--source-map`, discarding source maps
- Writes minified output using the pre-minification hashed filename
- The hash in the filename reflects pre-minification content, not the actual minified bytes

The terser CLI supports chaining source maps via `--source-map "content=<path>"`, which reads an existing `.map` file and produces a new `.map` that composes through the minification transform.

## Goals / Non-Goals

**Goals:**
- Chained source maps through minification: minified `.map` files trace back to original Scala sources
- Post-minification content hashing: filenames reflect the actual served bytes
- Cross-module import rewriting in minified output using post-minification hashes
- Correct `sourceMappingURL` comments in minified JS pointing to post-minification-hashed `.map` files

**Non-Goals:**
- Changing how `fullLinkJS` hashing works (that's correct as-is)
- Re-hashing source map files independently (they're keyed to their JS counterpart)
- Supporting terser config changes for source map behaviour (we always chain)

## Decisions

### 1. Post-minification re-hashing with `applyContentHash`

**Decision**: After terser runs all files into a temp directory, call the existing `applyContentHash` helper on the terser output. This re-hashes based on minified content, rewrites cross-module imports, and patches `sourceMappingURL`.

**Rationale**: `applyContentHash` already handles the full pipeline (toposort, hash, rewrite refs, rename maps, build updated name mapping). Reusing it avoids duplicating that logic. The terser output is structurally identical to linker output (JS files + `.map` files with `sourceMappingURL` comments), so the same processing applies.

**Pre-condition**: Terser output filenames must not contain a pre-minification hash. We strip it before writing terser output so that `applyContentHash` sees clean `<base>.js` names and applies a single hash.

### 2. Strip pre-minification hash before terser output

**Decision**: When writing terser output to the temp directory, use the "base" name (with hash stripped) rather than the hashed input name. E.g. `main.a1b2c3d4.js` → terser output written as `main.js` in temp.

**Rationale**: This ensures `applyContentHash` applies exactly one hash segment. The alternative (keeping the pre-hash and stripping later) would require modifying `applyContentHash` to understand double-hashed names.

**Implementation**: Split filename on `.`, drop the second-to-last segment (the 16-hex-char hash). The pattern `<base>.<16hexchars>.<ext>` is unambiguous because we control the input.

### 3. Terser `--source-map` argument via `os.proc`

**Decision**: Pass `--source-map` as a single argument with key-value pairs: `content=<input.map>,filename=<output.map>`.

**Rationale**: `os.proc` passes arguments directly to the process without shell interpretation, so no shell quoting is needed. The terser CLI parses the value string internally. The `content=` key tells terser to read the input source map for chaining. The `filename=` key controls where the output `.map` is written.

### 4. Build a name-stripping helper, not modify `applyContentHash`

**Decision**: Add a small helper (`stripContentHash`) that removes the hash segment from a filename. Keep `applyContentHash` unchanged.

**Rationale**: `applyContentHash` is well-tested and used by both `ContentHashScalaJSModule` and `FileBasedContentHashScalaJSModule`. Modifying it to handle pre-hashed input would add complexity to a critical path. A focused helper is simpler and testable in isolation.

## Risks / Trade-offs

- **Terser source map fidelity** → Terser's source map composition is well-established; the `content=` flag has been stable across versions. Low risk.
- **Double I/O** → We write terser output to a temp dir, then `applyContentHash` reads and writes to `Task.dest`. This is one extra read/write cycle. Acceptable given these are minified files (smaller than originals).
- **Hash stripping fragility** → If a JS base name happens to contain a segment that looks like a 16-hex-char hash, it could be incorrectly stripped. In practice, Scala.js module IDs don't produce such names, and we control the input format. The helper can validate the segment is exactly 16 hex chars.
