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
