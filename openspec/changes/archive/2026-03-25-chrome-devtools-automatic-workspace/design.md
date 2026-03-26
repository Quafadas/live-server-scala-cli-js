## Context

Chrome DevTools M-135+ supports "Automatic Workspace Folders" via a JSON descriptor served at `/.well-known/appspecific/com.chrome.devtools.json`. The `plugin.serve` Task.Worker starts an http4s server via `LiveServer.main(lcs: LiveServerConfig)`. All HTTP routing flows through `sjsls/src/routes.scala`. The Mill plugin constructs `LiveServerConfig` and passes it to `LiveServer.main`.

Currently `LiveServerConfig` (in `sjsls`) does not carry any workspace descriptor information, and `routes.scala` does not serve the well-known path.

## Goals / Non-Goals

**Goals:**

- Serve `GET /.well-known/appspecific/com.chrome.devtools.json` during `plugin.serve`, returning `{"workspace":{"root":"<absolute-project-root>","uuid":"<stable-v4-uuid>"}}`.
- The UUID must be stable across server restarts for the same project (so DevTools doesn't lose the workspace mapping on restart).
- Zero manual configuration for the developer.

**Non-Goals:**

- Generating or shipping the `com.chrome.devtools.json` file as a static asset on disk.
- Supporting DevTools workspace outside of `localhost` origins (the spec requires localhost; we don't need to enforce or bypass this).

## Decisions

### 1. Add `devToolsWorkspace: Option[(String, String)]` to `LiveServerConfig`

`LiveServerConfig` is the canonical configuration carrier between the Mill plugin and the `sjsls` library. Adding an `Option[(String, String)]` field (workspace root path + UUID) keeps the convention consistent with other optional features (e.g., `customRefresh`, `stylesDir`). Defaulting to `None` means the route is simply absent for all existing callers that do not supply it.

**Alternative considered**: Two separate `Option[String]` fields (`devToolsWorkspaceRoot`, `devToolsWorkspaceUuid`). Rejected — they are always needed together; a tuple communicates that invariant.

**Alternative considered**: Serve the JSON entirely from the Mill plugin side, outside of the library. This would require forking the http4s server startup or injecting routes after `LiveServer.main` returns, which is not supported by the current API (it returns the server resource directly, not a router builder).

### 2. Add the well-known route in `routes.scala`

`routes.scala` already combines all routes and is the single composition point. Adding a conditional `HttpRoutes` branch for `devToolsWorkspaceRoot.isDefined` here is consistent with how other optional route groups are handled (proxy routes, SPA routes).

The JSON is cheap to compute inline; no static file or template needed.

### 3. UUID stability via a `Task` in the Mill plugin

A simple `def devToolsUuid = Task { java.util.UUID.randomUUID().toString }` Mill task generates the UUID once. Mill caches task outputs based on input hashes; because this task has no tracked inputs its fingerprint is stable between runs, so the cached UUID persists until `mill clean` is invoked. This is idiomatic Mill and requires no manual file persistence.

**Alternative considered**: Write the UUID to a dotfile in `moduleDir` (source tree). Rejected — pollutes the source tree and falls outside Mill conventions.

### 4. Plugin passes `moduleDir` as workspace root

`moduleDir` is the Mill module's source directory (the project root from the developer's perspective). This is the correct root for DevTools because it is the directory that contains the source files the developer edits.

### 5. CLI gains `--workspace-root` and `--workspace-uuid` optional arguments

The `CliOpts` object in `sjsls` already defines all CLI flags via `decline`. Two new optional flags are added: `--workspace-root` (path string) and `--workspace-uuid` (UUID string, auto-generated when omitted). When `--workspace-root` is supplied, `LiveServerConfig.devToolsWorkspaceRoot` is set and the route is served. When absent the route is not served, preserving existing behaviour.

Storing root and UUID together in `LiveServerConfig` as `Option[(String, String)]` (rather than two separate fields) keeps them cohesive — the route either needs both or neither.

## Risks / Trade-offs

- [UUID regeneration on `mill clean`] → A `mill clean` will invalidate the cached `devToolsUuid` task, generating a new UUID. DevTools will then treat it as a new workspace. This is acceptable; `mill clean` is an explicit developer action. Mitigation: document this behaviour.
- [Chrome version requirement] → The feature requires Chrome M-135+. The server always serves the well-known URL when the plugin is used; older Chrome simply ignores it. No mitigation needed.
- [LiveServerConfig source compatibility] → Adding a field with a default value to `LiveServerConfig` is backward-compatible for callers using named arguments, which is the established pattern in this codebase.
- [UUID auto-generation in CLI with no persistence] → When the CLI auto-generates a UUID (no `--workspace-uuid` supplied), it will differ on each restart. Developers who want stability should supply `--workspace-uuid` explicitly or use the Mill plugin which caches the UUID via Mill task outputs.
