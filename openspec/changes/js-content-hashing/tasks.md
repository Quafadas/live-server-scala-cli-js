## 1. Filename Sanitisation

- [ ] 1.1 Add hyphen-to-underscore replacement in `applyContentHash` (both `ContentHashScalaJSModule` and `FileBasedContentHashScalaJSModule` companion objects) so that generated filenames replace "-" with "_" before hashing
- [ ] 1.2 Add unit tests for filename sanitisation in `ContentHashScalaJSModuleSuite` — verify that a file named `my-module.js` becomes `my_module.<hash>.js`

## 2. In-Memory JS Hashing

- [ ] 2.1 Implement the JS content hashing path in `InMemoryHashScalaJSModule.fastLinkJS` (currently `???`) — read JS files from `inMemoryOutputDirectory`, apply topological sort, rewrite references, compute hashes, and write hashed files to `Task.dest`
- [ ] 2.2 Add unit tests for the in-memory JS hashing path verifying correct output filenames and cross-module reference rewriting

## 3. Integration Tests

- [ ] 3.1 Update `js.test.scala` integration test to verify that hashed filenames do not contain hyphens (all "-" replaced with "_")
- [ ] 3.2 Complete `linkInMem.test.scala` integration test to verify in-memory hashing produces correct hashed output with valid cross-module references
- [ ] 3.3 Add an integration test scenario that verifies hash cascading — changing a dependency's source changes both the dependency's and the importer's hashed filenames

## 4. Spec Updates

- [ ] 4.1 Update `openspec/specs/plugin/spec.md` to include the content hashing requirement and scenarios from this change
