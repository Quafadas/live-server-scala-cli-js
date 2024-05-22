# An experiment in a dev server for scala JS

Try and break the dependance on node / npm completely whilst retaining a sane developer experience for browser based scala-js development.

## Goals

Replicate the "experience" of using vite with scala JS. 

- Live reload / link on change
- Hot application of style (no page reload)
- Proxy server
- page open on start

## Contraints

- Scala cli to build frontend
- ESModule output (only)
- Third party ESModules via import map rather than npm
- Styles through LESS

## Contributing

CI builds a container image which is ready to roll. 

```