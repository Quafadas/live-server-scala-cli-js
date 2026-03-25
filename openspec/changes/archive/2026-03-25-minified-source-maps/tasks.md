## 1. Hash Stripping Helper

- [x] 1.1 Add `stripContentHash(name: String): String` to `FileBasedContentHashScalaJSModule` companion object that removes the 16-hex-char hash segment from a content-hashed filename (e.g. `main.a1b2c3d4e5f6g7h8.js` → `main.js`)
- [x] 1.2 Add unit tests for `stripContentHash` covering: single-segment base names, underscore-containing base names, and names that are already unhashed (passthrough)

## 2. Minified Task — Source Maps and Re-Hashing

- [x] 2.1 Update `minified` task to pass `--source-map "content=<input>.map"` to terser so it chains source maps through minification
- [x] 2.2 Update `minified` task to write terser output to a temp directory using stripped (unhashed) filenames
- [x] 2.3 Call `applyContentHash` on the temp directory output to re-hash based on minified content, rewrite cross-module imports, and patch `sourceMappingURL`

## 3. Integration Tests

- [x] 3.1 Add integration test verifying that `minified` output JS files have source maps (each `.js` has a corresponding `.js.map`)
- [x] 3.2 Add integration test verifying that `minified` filenames contain post-minification hashes that differ from the `fullLinkJS` hashes
- [x] 3.3 Add integration test verifying that `sourceMappingURL` in minified JS points to the correctly-named `.map` file

## 4. Spec Updates

- [x] 4.1 Update `openspec/specs/content-hashing/spec.md` with the modified minified task requirement from this change
