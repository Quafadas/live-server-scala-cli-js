## ADDED Requirements

### Requirement: publish task produces a self-contained deployment site

`ScalaJsWebAppModule.publish` SHALL copy the minified, fully-linked JS output and a correctly generated `index.html` into `Task.dest`, producing a directory that can be deployed as a static site without any external references to other Mill output directories.

#### Scenario: index.html references minified JS filenames

- **WHEN** `publish` is invoked on a module extending `ScalaJsWebAppModule`
- **THEN** the generated `index.html` SHALL contain `<script>` tags whose `src` attributes reference the content-hashed filenames from the `minified()` task's `publicModules`
- **AND** those JS files SHALL be present in `Task.dest`

#### Scenario: All minified JS files are present in Task.dest

- **WHEN** `publish` is invoked
- **THEN** every JS file (and corresponding source map) produced by `minified()` SHALL be copied into `Task.dest`

#### Scenario: Assets are copied when assetsDir exists

- **WHEN** `publish` is invoked
- **AND** the `assetsDir` path exists on disk
- **THEN** the assets SHALL be copied into `Task.dest` with `mergeFolders = true`

#### Scenario: Assets are not required

- **WHEN** `publish` is invoked
- **AND** the `assetsDir` path does not exist on disk
- **THEN** the task SHALL succeed without attempting to copy assets

#### Scenario: Published HTML omits live-reload script

- **WHEN** `publish` is invoked
- **THEN** the generated `index.html` SHALL NOT contain the SSE live-reload script (`/refresh/v1/sse`)
