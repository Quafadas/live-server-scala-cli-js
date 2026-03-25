## ADDED Requirements

### Requirement: Serve Chrome DevTools workspace descriptor at well-known URL

During `plugin.serve`, the dev server SHALL respond to `GET /.well-known/appspecific/com.chrome.devtools.json` with a JSON body containing the absolute path to the project root and a stable UUID, enabling Chrome DevTools M-135+ to automatically mount the workspace folder.

#### Scenario: Well-known URL returns workspace JSON

- **WHEN** a GET request is made to `/.well-known/appspecific/com.chrome.devtools.json` while `plugin.serve` is running
- **THEN** the response SHALL have status `200 OK`
- **AND** the `Content-Type` header SHALL be `application/json`
- **AND** the response body SHALL be valid JSON with the shape `{"workspace":{"root":"<absolute-path>","uuid":"<uuid>"}}`

#### Scenario: Workspace root is the Mill module directory

- **WHEN** the workspace JSON is served
- **THEN** the `workspace.root` field SHALL contain the absolute filesystem path of the Mill module's `moduleDir`

#### Scenario: UUID is stable across restarts

- **WHEN** `plugin.serve` is restarted without running `mill clean`
- **THEN** the `workspace.uuid` in the response SHALL be identical to the UUID returned in the previous server session

#### Scenario: Well-known URL absent when no workspace root is configured

- **WHEN** the sjsls server is started without a workspace root configured (neither via CLI arguments nor via the Mill plugin)
- **THEN** a GET request to `/.well-known/appspecific/com.chrome.devtools.json` SHALL return `404 Not Found`

#### Scenario: CLI usage with workspace root argument serves workspace JSON

- **WHEN** the sjsls server is started via the standalone CLI with `--workspace-root <path>` and `--workspace-uuid <uuid>` arguments
- **THEN** a GET request to `/.well-known/appspecific/com.chrome.devtools.json` SHALL return `200 OK`
- **AND** the response body SHALL contain `{"workspace":{"root":"<path>","uuid":"<uuid>"}}`

#### Scenario: CLI usage with workspace root but no UUID generates a UUID automatically

- **WHEN** the sjsls server is started via the standalone CLI with `--workspace-root <path>` but without `--workspace-uuid`
- **THEN** the server SHALL generate a UUID automatically and serve the workspace JSON at the well-known URL
