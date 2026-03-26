## Why

Chrome DevTools M-135+ supports "Automatic Workspace Folders": when a dev server serves `/.well-known/appspecific/com.chrome.devtools.json` with the project root and a UUID, DevTools automatically maps sources to the local filesystem without any manual configuration. This gives developers edit-and-persist-from-DevTools for free during `plugin.serve`.

## What Changes

- The `plugin.serve` task SHALL serve `/.well-known/appspecific/com.chrome.devtools.json` at the live-reload dev server.
- The JSON response SHALL contain `{"workspace": {"root": "<mill-project-root>", "uuid": "<stable-v4-uuid>"}}`.
- The UUID SHALL be stable across restarts (written to a file in the Mill out directory on first generation and reused thereafter).
- No changes to the build, publish, or non-serve tasks.

## Capabilities

### New Capabilities

- `chrome-devtools-workspace`: Serves the Chrome DevTools workspace descriptor JSON at the well-known URL during `plugin.serve`, enabling automatic workspace folder integration in Chrome DevTools M-135+.

### Modified Capabilities

- `plugin`: The `serve` behaviour in the Mill plugin gains the new well-known route. No requirement-level changes to existing behaviour.

## Impact

- `plugin/src/refresh_plugin.scala` — `ScalaJsRefreshModule.serve` task, or the http4s route setup used by the dev server, needs the new route added.
- A UUID must be generated and persisted (e.g. in `T.dest` or `os.home` Mill cache) so it is stable across restarts.
- No impact on `routes/`, `sjsls/` library, or publish pipelines.
- No breaking changes.
