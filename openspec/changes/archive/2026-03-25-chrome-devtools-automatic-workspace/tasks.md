## 1. sjsls Library — LiveServerConfig

- [x] 1.1 Add `devToolsWorkspace: Option[(String, String)] = None` field (root path, UUID) to `LiveServerConfig` in `sjsls/src/LiveServerConfig.scala`

## 2. sjsls Library — Well-Known Route

- [x] 2.1 In `sjsls/src/routes.scala`, add a helper function `devToolsRoute` that, given `Option[(String, String)]` (root, uuid), returns an `HttpRoutes[IO]` responding to `GET /.well-known/appspecific/com.chrome.devtools.json` with the JSON body and `Content-Type: application/json`
- [x] 2.2 In `routes`, prepend `devToolsRoute(lsc.devToolsWorkspace)` to the combined routes

## 3. sjsls CLI — New Arguments

- [x] 3.1 In `sjsls/src/CliOpts.scala`, add `--workspace-root` (`Option[String]`) and `--workspace-uuid` (`Option[String]`) decline opts
- [x] 3.2 In `LiveServer.parseOpts`, combine the two new opts and map them into `devToolsWorkspace`: if `workspace-root` is `Some`, generate a UUID from `workspace-uuid` or `java.util.UUID.randomUUID()` when absent; pass the result as `Option[(String, String)]` to `LiveServerConfig`

## 4. Mill Plugin — UUID Task

- [x] 4.1 In `plugin/src/refresh_plugin.scala` on `ScalaJsRefreshModule`, add `def devToolsUuid = Task { java.util.UUID.randomUUID().toString }` — Mill caches this result so UUID is stable across restarts without `mill clean`

## 5. Mill Plugin — Wire into lcs

- [x] 5.1 In the `lcs` Task.Worker in `ScalaJsRefreshModule`, set `devToolsWorkspace = Some((moduleDir.toString(), devToolsUuid()))`

## 6. Tests

- [x] 6.1 Add a munit test in `sjsls/test/src/` that starts the dev server with `devToolsWorkspace = Some(("/test/root", "test-uuid"))`, makes a GET to `/.well-known/appspecific/com.chrome.devtools.json`, and asserts `200 OK`, `application/json` content-type, and correct JSON body
- [x] 6.2 Add a munit test asserting that when `devToolsWorkspace = None` the well-known URL returns `404`
