## MODIFIED Requirements

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

#### Scenario: ScalaJsRefreshModule without content hashing is unchanged
- **WHEN** `siteGen` is invoked on a module extending only `ScalaJsRefreshModule` (without content hashing)
- **THEN** the generated `index.html` SHALL contain `<script src="/main.js">`
- **AND** behaviour SHALL be identical to the current implementation
