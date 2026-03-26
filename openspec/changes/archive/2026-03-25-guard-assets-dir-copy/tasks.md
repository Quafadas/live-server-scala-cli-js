## 1. Guard asset copying in siteGen

- [x] 1.1 In `siteGen`, wrap the `os.copy(assets_.path, Task.dest, mergeFolders = true)` call with an `if os.exists(assetsDir)` guard
- [x] 1.2 In `siteGenFull`, apply the same `if os.exists(assetsDir)` guard around the assets copy

## 2. Verify

- [x] 2.1 Run `./mill plugin.test` to confirm existing tests still pass
- [x] 2.2 Run `./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources` to ensure formatting is correct
