## Context

`ScalaJsRefreshModule` in `plugin/src/refresh_plugin.scala` defines `assetsDir` as `super.moduleDir / "assets"` and an `assets` `Task.Source` that wraps it. The `siteGen` and `siteGenFull` tasks unconditionally evaluate `assets()` and call `os.copy(assets_.path, Task.dest, mergeFolders = true)`. If the directory doesn't exist on disk, the task fails.

## Goals / Non-Goals

**Goals:**
- Make `siteGen` and `siteGenFull` succeed when no `assets/` directory exists.
- Preserve existing behaviour when the directory does exist (assets are still copied).

**Non-Goals:**
- Changing the `assetsDir` path convention or making it user-configurable (already is via override).
- Adding new asset-processing features.

## Decisions

**Guard at the copy site rather than making `assets` optional.**
The `assets` `Task.Source` is a public API that downstream modules can override. Wrapping it in `Option` would be a breaking change. Instead, guard the `os.copy` calls with `os.exists(assetsDir)`. This keeps the public surface unchanged and is the minimal fix.

**Use `os.exists(assetsDir)` check in both `siteGen` and `siteGenFull`.**
Both tasks have the same pattern. Consistency is important — apply the same guard in both places.

## Risks / Trade-offs

- [Risk] Someone overrides `assetsDir` to a path that exists but is empty → no functional change, copy of empty dir is harmless.
- [Risk] Guard hides a misconfigured path (user typo) → acceptable because the previous behaviour was a hard crash, which is worse UX. Users who need assets will notice they're missing from the served site.
