# Copilot Instructions

## Project Overview

`live-server-scala-cli-js` (published as `io.github.quafadas::sjsls`) is a Scala JS live development server that aims to replicates the Vite.js experience without Vite. It provides:
- Live reload on file changes via Server-Sent Events (SSE)
- Hot CSS/LESS application without page reload
- HTTP proxy server support
- Auto-open browser on startup
- Support for Scala CLI, Mill, and no-build-tool modes

ToDo: Content hashing

## Build System: Mill

This project uses **Mill** (version 1.0.5) as its build tool. The Mill wrapper script (`./mill`) is checked in. JVM 21 is required.

CI will fail poorly formatted files. Make sure to run formatting before pushing.

## Discovery

To discover things about symbols in the codebase, use the scalex skill. e.g. `scalex search ContentHashScalaJSModule --kind trait` to find the location of the `ContentHashScalaJSModule` trait.

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
./mill mill.scalalib.scalafmt.ScalafmtModule/

# Linting (Scalafix)
./mill __.fix

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


## Technology Stack

The typelevel stack -

## Testing

Tests use **Munit** with **Cats Effect** and **Playwright** for browser integration tests.

Before running tests, you'll probably need to make sure that playwright is installed and ready. It should be installed as part of the `copilot-setup-steps` workflow.

```bash
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
