# plugin Specification

## Purpose

The mill plugin integrates with the mill build tool to provide a simple development server for scala-js projects.

### Requirement: Refresh on change

The tool should be able to detect changes in the source code and refresh the application accordingly.

#### SCENARIO:
- GIVEN a build tool has supplied a event stream
- WHEN that event stream emits a pulse with a refresh event
- THEN the server should emit a server side event to the client
- AND the client should refresh the page (in the presence of the right javascript)

#### SCENARIO:
- GIVEN that sjsls is the entry point to an application (i.e. was run from the command line standalone)
- WHEN that a build tool (e.g. scala-cli) has finished linking
- THEN the server should emit a server side event to the client
- AND the client should refresh the page (in the presence of the right javascript)

### Requirement: Content hashing

The plugin SHALL support content hashing of generated JavaScript files to enable immutable browser caching.
Mixing `ContentHashScalaJSModule` or `FileBasedContentHashScalaJSModule` into a `ScalaJSModule` activates this
behaviour transparently on `fastLinkJS` and `fullLinkJS`.

When `ScalaJsWebAppModule` is used, the generated `index.html` SHALL reference the content-hashed entry-point JS filenames from the linker report rather than a static `/main.js` path. The `siteGen` and `siteGenFull` tasks SHALL copy all linked JS output files into the site destination directory.

The `publish` task on `ScalaJsWebAppModule` SHALL generate `index.html` using the content-hashed filenames from the `minified()` task's `publicModules` report (not from `fastLinkJS()`), so that the deployed HTML correctly references the files present in `Task.dest`.

#### SCENARIO:
- GIVEN that the build tool has generated a javascript file
- WHEN the `fastLinkJS` or `fullLinkJS` tasks are invoked
- THEN the generated javascript file SHALL be content-hashed and the hash SHALL be included in the filename in the form `<base>.<hash>.js`
- AND all cross-module import references within the JS output SHALL be rewritten to use the hashed filenames
- AND the `sourceMappingURL` comments SHALL reference the corresponding hashed source map filenames

#### SCENARIO:
- GIVEN that a generated javascript filename contains a hyphen (e.g. `my-module.js`)
- WHEN content hashing is applied
- THEN the hyphen SHALL be replaced with an underscore in the output filename (e.g. `my_module.<hash>.js`)
- AND no hashed output filename SHALL contain a hyphen

#### Scenario: ScalaJsWebAppModule generates HTML with hashed script references
- **WHEN** `siteGen` or `siteGenFull` is invoked on a module extending `ScalaJsWebAppModule`
- **THEN** the generated `index.html` SHALL contain `<script>` tags whose `src` attributes reference the actual hashed JS filenames from the linker report's `publicModules`
- **AND** all linked JS output files SHALL be copied into the site destination directory alongside `index.html`

#### Scenario: ScalaJsWebAppModule publish task generates HTML with minified hashed script references
- **WHEN** `publish` is invoked on a module extending `ScalaJsWebAppModule`
- **THEN** the generated `index.html` SHALL contain `<script>` tags whose `src` attributes reference the content-hashed filenames from the `minified()` report's `publicModules`
- **AND** those exact JS files SHALL be present in `Task.dest`
- **AND** the `index.html` SHALL NOT reference any filenames produced by `fastLinkJS()`
- **AND** the `index.html` SHALL NOT contain the SSE live-reload script

#### Scenario: ScalaJsRefreshModule without content hashing is unchanged
- **WHEN** `siteGen` is invoked on a module extending only `ScalaJsRefreshModule` (without content hashing)
- **THEN** the generated `index.html` SHALL contain `<script src="/main.js">`
- **AND** behaviour SHALL be identical to the current implementation

### Requirement: Site generation with optional assets

The `siteGen` and `siteGenFull` tasks SHALL copy the assets directory into the task destination only when the assets directory exists on disk. When the assets directory does not exist, the tasks SHALL succeed without copying assets.

#### Scenario: Assets directory exists
- **WHEN** the `siteGen` or `siteGenFull` task is invoked
- **AND** the `assetsDir` path exists on disk
- **THEN** the assets SHALL be copied into the task destination with `mergeFolders = true`

#### Scenario: Assets directory does not exist
- **WHEN** the `siteGen` or `siteGenFull` task is invoked
- **AND** the `assetsDir` path does not exist on disk
- **THEN** the task SHALL succeed without attempting to copy assets
- **AND** the generated site SHALL still contain `index.html` and linked JS output

### Requirement: plugin.serve exposes Chrome DevTools workspace descriptor

The `ScalaJsRefreshModule.serve` Task.Worker SHALL configure the live-reload server to serve the Chrome DevTools workspace descriptor JSON at `/.well-known/appspecific/com.chrome.devtools.json`, using the module's `moduleDir` as the workspace root and a stable per-project UUID.

#### Scenario: serve passes workspace root to LiveServerConfig

- **WHEN** `plugin.serve` is invoked
- **THEN** the `LiveServerConfig` passed to `LiveServer.main` SHALL have `devToolsWorkspace` set to `Some((<moduleDir absolute path>, <uuid>))`

#### Scenario: devToolsUuid task is stable without mill clean

- **WHEN** `devToolsUuid` is evaluated more than once without running `mill clean`
- **THEN** it SHALL return the same UUID string on every evaluation
