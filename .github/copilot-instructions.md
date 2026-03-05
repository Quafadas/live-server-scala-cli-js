# Copilot Instructions

## Project Overview

`live-server-scala-cli-js` (published as `io.github.quafadas::sjsls`) is a Scala JS live development server that replicates the Vite.js experience without Vite. It provides:
- Live reload on file changes via Server-Sent Events (SSE)
- Hot CSS/LESS application without page reload
- HTTP proxy server support
- Auto-open browser on startup
- Support for Scala CLI, Mill, and no-build-tool modes

## Build System: Mill

This project uses **Mill** (version 1.0.5) as its build tool. The Mill wrapper script (`./mill`) is checked in. JVM 21 is required.

### Mill Fundamentals

- `build.mill` is the root build file (header directives declare Mill version and JVM)
- Module dependencies and versions are centralised in the `V` object in `build.mill`
- Shared behaviour is expressed via traits: `FormatFix`, `FormatFixPublish`, `Testy`
- Individual modules define their own `package.mill` files
- Run `./mill resolve __` to list all available tasks
- Run `./mill <module>.<task>` to invoke a specific task, e.g. `./mill sjsls.compile`
- Use `./mill -w <task>` for watch mode (reruns on source change)

### Key Mill Commands

```bash
# IDE / setup
./mill mill.bsp.BSP/install          # Install BSP for Metals/IntelliJ

# Compilation
./mill __.compile                    # Compile all modules
./mill sjsls.compile                 # Compile main module only

# Testing
./mill __.test                       # Test all modules
./mill sjsls.test.testOnly io.github.quafadas.sjsls.RoutesSuite
./mill sjsls.test.testOnly io.github.quafadas.sjsls.SafariSuite
./mill sjsls.test.testOnly io.github.quafadas.sjsls.UtilityFcs

# Formatting (Scalafmt)
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources  # Reformat
./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources  # Check only (CI)

# Linting (Scalafix)
./mill __.fix

# Run the server
./mill sjsls.run -- --help           # Show all CLI options
./mill -w sjsls.runBackground -- --build-tool scala-cli --project-dir /path/to/project

# Publishing
./mill __.publishLocal               # Publish to local Ivy cache
# Tags (v*) trigger Sonatype Central publish in CI
```

### `justfile` Shortcuts

A `justfile` wraps common Mill commands. Run `just --list` to see all recipes:

```bash
just setupIde        # Install BSP
just compile         # Compile sjsls module
just test            # Run the three main test suites
just format          # Reformat all source files
just fix             # Run scalafix
just publishLocal    # Publish locally
just setupPlaywright # Install Playwright browsers for integration tests
```

## Module Structure

```
live-server-scala-cli-js/
├── build.mill              # Root Mill build; V object with all versions; shared traits
├── playwrightVersion.mill  # Playwright version constant (imported into build.mill)
├── justfile                # Task runner shortcuts
├── mill                    # Mill wrapper script
├── sjsls/                  # Main application module
│   ├── package.mill        # Module definition (extends FormatFixPublish)
│   ├── src/                # ~15 Scala source files
│   └── test/src/           # Munit + Playwright integration tests
├── routes/                 # HTTP routing library (depended on by sjsls)
│   ├── package.mill
│   ├── src/
│   └── test/src/
├── plugin/                 # Mill plugin for Scala JS live reload in user projects
│   ├── package.mill
│   └── src/refresh_plugin.scala
└── site/                   # Documentation site (Laika + SiteModule)
    ├── package.mill
    └── docs/
```

### Key Source Files in `sjsls/src/`

| File | Purpose |
|---|---|
| `liveServer.scala` | `IOApp` entry point; wires routes and starts Ember server |
| `LiveServerConfig.scala` | Configuration case class |
| `CliOpts.scala` | CLI argument parsing (decline) |
| `BuildTool.scala` | ADT: `ScalaCli | Mill | NoBuildTool` |
| `buildRunner.scala` | Invokes the configured build tool |
| `refreshRoute.scala` | SSE endpoint for triggering browser refresh |
| `staticRoutes.scala` | Static file serving |
| `staticWatcher.scala` | File system watcher (fs2 + os-lib) |
| `proxyHttp.scala` | HTTP reverse proxy |
| `openBrowser.scala` | Auto-opens browser at startup |
| `dezombify.scala` | Kills stale build processes |
| `sseReload.scala` | SSE event bus for reload signals |
| `ETagMiddleware.scala` | ETags for static assets |

## Technology Stack

| Layer | Library | Version |
|---|---|---|
| Scala | Scala 3 (LTS 3.3.6 for published libs, 3.7.2 for dev) | — |
| HTTP server/client | http4s (Ember) | 0.23.30 |
| Effects | cats-effect | 3.x |
| Streaming | fs2 | 3.11.0 |
| JSON | Circe | 0.14.10 |
| CLI parsing | decline + decline-effect | 2.5.0 |
| HTML generation | scalatags | 0.13.1 |
| Logging | scribe-cats | 3.15.0 |
| Scala JS | Scala JS | 1.19.0 |
| JS UI (browser side) | Laminar | 17.2.1 |
| Testing | Munit + munit-cats-effect + Playwright | 1.1.0 / 2.0.0 / 1.51.0 |
| File I/O (tests) | os-lib | 0.11.4 |
| Formatting | Scalafmt | (`.scalafmt.conf`) |
| Linting | Scalafix | (`.scalafix.conf`) |

## Scala Version Notes

- **Published modules** (`sjsls`, `routes`, `plugin`): Scala 3.3.6 LTS for broad compatibility
- **Development / site**: Scala 3.7.2
- The `V` object in `build.mill` is the single source of truth for all dependency versions

## Code Style and Conventions

- Scalafmt config: `.scalafmt.conf` — run `just format` before committing
- Scalafix config: `.scalafix.conf` — run `just fix` to apply rules
- Compiler flag `-Wunused:all` is active; remove any unused imports/values
- Effect type is `cats.effect.IO`; avoid blocking calls without `IO.blocking`
- Prefer `fs2.Stream` for streaming/file watching logic
- Pattern-match on the `BuildTool` ADT when adding build-tool-specific behaviour

## CLI Options (sjsls)

Run `./mill sjsls.run -- --help` for the full list. Key flags:

| Flag | Description |
|---|---|
| `--build-tool` | `scala-cli`, `mill`, or `none` |
| `--project-dir` | Root directory of the user's project |
| `--port` | HTTP port (default 3000) |
| `--proxy-target-port` | Backend port to proxy API calls to |
| `--proxy-prefix-path` | URL prefix to forward to proxy target |
| `--log-level` | `trace`, `debug`, `info`, `warn`, `error` |
| `--browse-on-open-at` | Path to auto-open in browser |
| `--styles-dir` | Directory containing `index.less` |
| `--path-to-index-html` | Directory serving `index.html` |
| `--mill-module-name` | Mill module name (when `--build-tool mill`) |

## Testing

Tests use **Munit** with **Cats Effect** and **Playwright** for browser integration tests.

```bash
# Install Playwright browsers once per machine
just setupPlaywright

# Run individual suites
./mill sjsls.test.testOnly io.github.quafadas.sjsls.SafariSuite
./mill sjsls.test.testOnly io.github.quafadas.sjsls.RoutesSuite
./mill sjsls.test.testOnly io.github.quafadas.sjsls.UtilityFcs

# Run all tests
./mill __.test
```

Tests that start a real server use `IOSuite` from munit-cats-effect and allocate an `http4s` client as a resource. Playwright tests launch a headless browser to verify live reload behaviour.

## CI/CD

The CI pipeline (`.github/workflows/ci.yml`) runs on PRs and pushes to main:

1. **Format check**: `./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources`
2. **Compile**: `./mill __.compile`
3. **Test**: `./mill __.test`

Publishing to Maven Central is triggered automatically when a `v*` tag is pushed. The Copilot agent setup workflow (`.github/workflows/copilot-setup-steps.yml`) pre-compiles semantic DB files for IDE support.

## Publishing

- Group ID: `io.github.quafadas`
- Artifact IDs: `sjsls`, `sjsls-routes`, `sjsls-plugin`
- Published via `mill.javalib.SonatypeCentralPublishModule`
- Version is derived from VCS state: `VcsVersion.vcsState().format()`
- To test a local publish: `./mill __.publishLocal`

## Common Development Workflows

### Adding a new CLI option
1. Add the field to `LiveServerConfig` in `sjsls/src/LiveServerConfig.scala`
2. Add the corresponding `Opts` entry in `sjsls/src/CliOpts.scala`
3. Wire it into the config construction in `CliOpts`
4. Use the new field in the appropriate source file

### Adding a new HTTP route
1. Define the route in `routes/src/` (or `sjsls/src/` for server-only routes)
2. Register it in `sjsls/src/routes.scala`
3. Add a corresponding test in `sjsls/test/src/` using Munit + http4s client

### Supporting a new build tool
1. Add a case to the `BuildTool` ADT in `sjsls/src/BuildTool.scala`
2. Add invocation logic in `sjsls/src/buildRunner.scala`
3. Handle the new case in `CliOpts.scala`

### Updating a dependency
Edit the version in the `V` object in `build.mill`, then run `./mill __.compile` to verify.
