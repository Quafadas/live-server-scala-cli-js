## 1. Fix publish task implementation

- [x] 1.1 Rewrite the `publish` task body in `ScalaJsWebAppModule` (`plugin/src/refresh_plugin.scala`) to build `index.html` from `indexHtmlHead()` and a body constructed from `minified().publicModules` (no SSE script)
- [x] 1.2 Copy all files from `minified().dest.path` into `Task.dest` (JS + source maps)
- [x] 1.3 Copy assets into `Task.dest` when `assetsDir` exists (guarded with `os.exists`)

## 2. Update plugin spec

- [x] 2.1 Merge the delta spec (`openspec/changes/webapp-publish-task/specs/plugin/spec.md`) into `openspec/specs/plugin/spec.md` — add the new `publish` scenario to the existing "Content hashing" requirement

## 3. Tests

- [x] 3.1 Add a utest case in `plugin/test/src/ContentHashScalaJSModuleSuite.scala` (or appropriate suite) that invokes `publish`, checks `index.html` contains `<script>` src values matching filenames present in `Task.dest`, and confirms the SSE script is absent
- [x] 3.2 Verify the test passes with `./mill plugin.test`

## 4. Compile check

- [x] 4.1 Run `./mill plugin.compile` to confirm no compilation errors
