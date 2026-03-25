## 1. Override siteGen in ScalaJsWebAppModule

- [x] 1.1 Override `siteGen` in `ScalaJsWebAppModule` to: read `publicModules` from `fastLinkJS()`, generate `index.html` with `<script>` tags referencing the hashed JS filenames, copy all linked JS output files into `Task.dest`
- [x] 1.2 Override `siteGenFull` in `ScalaJsWebAppModule` with the same pattern using `fullLinkJS()`

## 2. Verify

- [x] 2.1 Run `./mill plugin.test` to confirm existing tests still pass
- [x] 2.2 Run `./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources` to ensure formatting is correct
