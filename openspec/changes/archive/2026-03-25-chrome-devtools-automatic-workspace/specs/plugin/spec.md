## ADDED Requirements

### Requirement: plugin.serve exposes Chrome DevTools workspace descriptor

The `ScalaJsRefreshModule.serve` Task.Worker SHALL configure the live-reload server to serve the Chrome DevTools workspace descriptor JSON at `/.well-known/appspecific/com.chrome.devtools.json`, using the module's `moduleDir` as the workspace root and a stable per-project UUID.

#### Scenario: serve passes workspace root to LiveServerConfig

- **WHEN** `plugin.serve` is invoked
- **THEN** the `LiveServerConfig` passed to `LiveServer.main` SHALL have `devToolsWorkspaceRoot` set to `Some(<moduleDir absolute path>)`

#### Scenario: devToolsUuid task is stable without mill clean

- **WHEN** `devToolsUuid` is evaluated more than once without running `mill clean`
- **THEN** it SHALL return the same UUID string on every evaluation
