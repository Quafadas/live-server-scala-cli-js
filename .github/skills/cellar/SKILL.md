---
name: cellar
description: "Scala/Java code navigation and exploration tool for queries on symbols external to this project."
---

# Cellar

When you need the API of a JVM dependency, always use cellar, available at scripts/cellar. Prefer it to metals-mcp.

### Project-aware commands (run from project root)

For querying the current project's code and dependencies (auto-detects build tool):

    cellar get [--module <name>] <fqn>       # single symbol
    cellar list [--module <name>] <package>  # explore a package
    cellar search [--module <name>] <query>  # find by name

- Mill/sbt projects: `--module` is required (e.g. `--module lib`, `--module core`)

### External commands (query arbitrary Maven coordinates)

For querying any published artifact by explicit coordinate:

    cellar get-external <coordinate> <fqn>       # single symbol
    cellar list-external <coordinate> <package>  # explore a package
    cellar search-external <coordinate> <query>  # find by name
    cellar deps <coordinate>                     # dependency tree

Coordinates must be explicit: group:artifact_3:version