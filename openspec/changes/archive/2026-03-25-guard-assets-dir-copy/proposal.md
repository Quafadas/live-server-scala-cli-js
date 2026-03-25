## Why

The `siteGen` and `siteGenFull` tasks in `ScalaJsRefreshModule` unconditionally call `assets()` and copy the assets directory into the task destination. If the `assets/` directory does not exist under the module directory, the task fails with a file-not-found error. Users who don't need an assets directory are forced to create an empty one. The copy should be guarded so it only runs when the directory actually exists.

## What Changes

- Guard the `os.copy(assets_.path, Task.dest, mergeFolders = true)` call in `siteGen` so it only executes when `assetsDir` exists on disk.
- Apply the same guard in `siteGenFull`.
- Make the `assets` Task.Source conditional or tolerant of a missing directory.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `plugin`: The `siteGen` and `siteGenFull` tasks will no longer require the assets directory to exist. Assets copying becomes optional.

## Impact

- **Code**: `plugin/src/refresh_plugin.scala` — `siteGen`, `siteGenFull`, and potentially `assets` definitions in `ScalaJsRefreshModule`.
- **Behaviour**: Projects without an `assets/` directory will work without errors. No breaking change for projects that already have the directory.
