## 1. Filename Sanitisation

- [x] 1.1 Add hyphen-to-underscore replacement in `applyContentHash` (both `ContentHashScalaJSModule` and `FileBasedContentHashScalaJSModule` companion objects) so that generated filenames replace "-" with "_" before hashing
- [x] 1.2 Add unit tests for filename sanitisation in `ContentHashScalaJSModuleSuite`

## 2. In-Memory JS Hashing

- [x] 2.1 Implement the JS content hashing path in `InMemoryHashScalaJSModule.fastLinkJS`
- [x] 2.2 Add unit tests for the in-memory JS hashing path verifying correct output filenames and cross-module reference rewriting

## 3. Integration Tests

- [x] 3.1 Update `js.test.scala` integration test to verify that hashed filenames do not contain hyphens
- [x] 3.2 Complete `linkInMem.test.scala` integration test to verify in-memory hashing produces correct hashed output with valid cross-module references
- [x] 3.3 Add an integration test scenario that verifies hash cascading

## 4. Spec Updates

- [x] 4.1 Update `openspec/specs/plugin/spec.md` to include the content hashing requirement and scenarios from this change
