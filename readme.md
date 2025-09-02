See [docs](https://quafadas.github.io/live-server-scala-cli-js/)

# Scala JS live server TL;DR

> Show me scala JS - I have 20 seconds.

Paste this into your terminal and hit enter.

```sh

scala-cli --version && \
cs version && \
git clone https://github.com/Quafadas/viteless.git && \
cd viteless && \
cs launch io.github.quafadas::sjsls:{{projectVersion}}
```

## Goals

Replicate the "experience" of using vite with scala JS. Without vite.

- Live reload / link on change
- Hot application of style (no page reload)
- Proxy server
- page open on start

## Assumptions

`cs`, `scala-cli` and `mill` are readily available on the path.
The entry point for styles is `index.less`, and that file exists in the styles directory. It can link to other style files.
App must be mounted to a div, with id `app`.


## Contributing

It's so welcome. Start a dicsussion if you'd like so ideas! CI builds a container image which is ready to roll.
