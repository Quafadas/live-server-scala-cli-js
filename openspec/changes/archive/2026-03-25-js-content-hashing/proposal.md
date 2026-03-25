## Why

The tool currently outputs JS files with their original names (e.g. `main.js`), which requires cache-busting strategies on the server side. Content hashing embeds a SHA-256 fingerprint into each filename (e.g. `main.a1b2c3d4.js`), enabling immutable caching — browsers can cache files forever and automatically fetch new versions when content changes. This is standard practice in modern frontend tooling (Vite, Webpack) and is the next step toward production-ready Scala.js deployments.

## What Changes

- Add content hashing to the Mill plugin's `fastLinkJS` and `fullLinkJS` task outputs, producing files named `<base>.<hash>.js`
- Replace "-" with "_" in generated filenames to avoid issues with terser's external source map handling
- Rewrite cross-module import/export references within JS files to use the new hashed filenames
- Rewrite `sourceMappingURL` comments to reference hashed source map filenames
- Process files in topological (dependency-first) order so that import rewrites are reflected in the importer's content hash
- Support both file-based and in-memory linker output strategies
- Provide a `minified` task that runs terser on `fullLinkJS` output

## Capabilities

### New Capabilities
- `content-hashing`: Content hash JS output from Scala.js linking, rename files to `<base>.<hash>.js`, rewrite all internal references, and replace "-" with "_" in filenames for terser compatibility

### Modified Capabilities
- `plugin`: The refresh plugin module gains content-hashing traits that users mix into their `ScalaJSModule` definitions

## Impact

- **plugin module**: New traits (`ContentHashScalaJSModule`, `FileBasedContentHashScalaJSModule`, `InMemoryHashScalaJSModule`) and companion object helpers
- **Build integration**: `fastLinkJS` and `fullLinkJS` tasks produce hashed output transparently when the trait is mixed in
- **Downstream consumers**: Any code that reads JS filenames from the linker `Report` will see hashed names — the `Report` is updated accordingly
- **Terser/minification**: The "-" to "_" replacement in filenames ensures terser can process source maps without errors
- **Caching**: HTTP servers can set `Cache-Control: immutable` on hashed assets
