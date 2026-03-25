# sjsls Specification

## Purpose

sjsls is intended to be a small scala-js development utility which aids a developer writing a scala-js frontend. It offers reload-on-change functionality. It works in two contexts:

1. as an entry point to an application
2. integrated into a build tool

### Requirement: Refresh on change

The tool should be able to detect changes in the source code and refresh the application accordingly.

#### SCENARIO:
- GIVEN a build tool has supplied a event stream
- WHEN that event stream emits a pulse with a refresh event
- THEN the server should emit a server side event to the client
- AND the client should refresh the page (in the presence of the right javascript)

#### SCENARIO:
- GIVEN that sjsls is the entry point to an application (i.e. was run from the command line standalone)
- WHEN that a build tool (e.g. scala-cli) has finished linking
- THEN the server should emit a server side event to the client
- AND the client should refresh the page (in the presence of the right javascript)

### Requirement: No ETag or Last-Modified on immutable cached responses
When a response for a content-hashed URI is served with `Cache-Control: immutable`, the server SHALL NOT include `ETag` or `Last-Modified` headers in that response.

#### Scenario: Hashed URI strips ETag
- **WHEN** a client requests a URI whose path is detected as content-hashed
- **THEN** the response SHALL contain `Cache-Control: immutable`
- **AND** the response SHALL NOT contain an `ETag` header

#### Scenario: Hashed URI strips Last-Modified
- **WHEN** a client requests a URI whose path is detected as content-hashed
- **THEN** the response SHALL NOT contain a `Last-Modified` header

#### Scenario: Non-hashed URI retains ETag
- **WHEN** a client requests a URI that is NOT content-hashed but is known in the hash map
- **THEN** the response SHALL still contain an `ETag` header for revalidation

