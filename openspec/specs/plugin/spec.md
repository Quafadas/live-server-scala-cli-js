# plugin Specification

## Purpose

The mill plugin integrates with the mill build tool to provide a simple development server for scala-js projects.

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
