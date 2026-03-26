## Why

The `minified` task runs terser on `fullLinkJS` output but discards source maps and uses the pre-minification content hash in filenames. This means: (1) minified output can't be debugged because the source map chain is broken, and (2) the filename hash doesn't reflect the actual minified bytes, so `Cache-Control: immutable` is technically incorrect. Both need fixing for production-ready deployments.

## What Changes

- Pass `--source-map "content=<input>.map"` to terser so it chains the Scala.js source map through minification, producing a `.map` that traces back to original Scala sources
- Re-hash JS files **after** minification so filenames reflect the actual served content
- Strip the pre-minification hash from filenames before applying the post-minification hash (avoiding `base.<preHash>.<postHash>.js`)
- Rewrite cross-module import references in minified output to use post-minification hashed names
- Patch `sourceMappingURL` comments to reference the correctly-named post-minification source map

## Capabilities

### New Capabilities

### Modified Capabilities
- `content-hashing`: The minified task requirement gains source map chaining and post-minification content hashing. The existing "Minified task" spec scenario needs to be strengthened to require source maps and correct post-minification hashes.

## Impact

- **plugin module**: `FileBasedContentHashScalaJSModule.minified` task implementation changes — terser invocation gains `--source-map` args, and post-processing re-hashes and renames output
- **Source maps**: Minified output will have chained source maps pointing back to Scala sources via the Scala.js linker's map
- **Downstream consumers**: The `PathRef` returned by `minified` will contain files with different hashes than `fullLinkJS` (post-minification hashes instead of pre-minification)
- **Dependencies**: No new dependencies — terser already supports `content=` for input source maps
