## 1. FileBasedContentHashScalaJSModule — WASM skip

- [x] 1.1 In `FileBasedContentHashScalaJSModule.minified`, read `scalaJSExperimentalUseWebAssembly()` at the top of the task body
- [x] 1.2 When `true`, copy all files from the `fullLinkJS` output directory directly to `Task.dest` (skipping the terser loop); leave the existing terser path untouched for the `false` branch
- [x] 1.3 Update the scaladoc on `minified` to clarify the WASM skip behaviour and distinguish it from the `scalaJSMinify` linker flag

## 2. InMemoryHashScalaJSModule — terser on fullLinkJS

- [x] 2.1 Add `terserConfig` task to `InMemoryHashScalaJSModule` (same default JSON as `FileBasedContentHashScalaJSModule`)
- [x] 2.2 In `InMemoryHashScalaJSModule.fullLinkJS`, in the non-WASM branch: when `scalaJSMinify()` is `true`, for each JS file in `inMemoryOutputDirectory` run terser (write to temp file, invoke CLI, read result back) and replace the in-memory entry with the minified bytes before the existing hash-and-write-to-disk flow
- [x] 2.3 Ensure source map `sourceMappingURL` comments are updated to use the final hashed filename after terser rewrites them
- [x] 2.4 Leave the WASM branch of `fullLinkJS` and the entirety of `fastLinkJS` unchanged

## 3. Tests

- [x] 3.1 `wasm.test.scala`: call `minified` on `FileBasedContentHashScalaJSModule` with `scalaJSExperimentalUseWebAssembly = true`; assert task succeeds, output contains `.wasm` and JS loader files, and each JS file is byte-for-byte the same size as in `fullLinkJS` output
- [x] 3.2 `linkInMem.test.scala`: call `fullLinkJS` on `InMemoryHashScalaJSModule` with `scalaJSMinify = true` (default); assert the output JS files are smaller than their `fastLinkJS` counterparts (confirming terser ran)
- [x] 3.3 Call `fastLinkJS` on `InMemoryHashScalaJSModule`; assert in-memory output is present and that fastLinkJS does not write into the directory - i.e. no file IO.
- [x] 3.4 Call `fullLinkJS` on `InMemoryHashScalaJSModule`; assert that files are written to disk and that terser is invoked when `scalaJSMinify = true` and not invoked when `scalaJSMinify = false`
