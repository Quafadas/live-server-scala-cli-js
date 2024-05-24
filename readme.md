# An experiment in a dev server for scala JS

Try and break the dependance on node / npm completely whilst retaining a sane developer experience for browser based scala-js development.

[Blogpost](https://quafadas.github.io/Whimsy/2024/05/22/Viteless.html)

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

## Assumptions

`cs`, `scala-cli` and `mill` are readily available on the path.
The entry point for styles is `index.less`, and that file exists in the styles directory. It can link to other style files.
App must be mounted to a div, with id `app`.

## Contributing

CI builds a container image which is ready to roll.

## Example command

To run this from a shell, try something like this:

```sh
cs launch io.github.quafadas:live-server-scala-cli-js_3:0.0.9 -- --project-dir /Users/simon/Code/viteless --port 3000 --build-tool scala-cli --out-dir /Users/simon/Code/viteless/out --browse-on-open-at /
```