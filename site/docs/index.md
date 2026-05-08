# Scala JS live server

> Show me scala JS - I have 20 seconds.

Paste this into your terminal and hit enter.

```sh
scala-cli --version && \
cs version && \
git clone https://github.com/Quafadas/viteless.git && \
cd viteless && \
cs launch io.github.quafadas::sjsls:@VERSION@
```

Note that to work, you need cs and scala-cli to be on your path.

## Homebrew

If you have homebrew, you can install those tools with:

```sh
brew install quafadas/homebrew-sjsls/sjsls
```
This "tap" will download a scala-native compiled binary (zero startup time!) of the latest release of sjsls, and put it on your path as `sjsls`. You can also find it on [github](https://github.com/Quafadas/live-server-scala-cli-js/releases)

```sh
sjsls --init test2
cd test2
code .
sjsls --styles-dir assets
```
If you're into vibe coding,

> build a clock using scala laminar that rotates as the time changes

And watch the browser at https://localhost:3000 :-)...

## It worked... okay... I have 20 more seconds

Edit `hello.scala` and save the change. You should see the change refreshed in your browser.




## Aw shoot - errors

The command above assumes you have coursier (as cs) and scala-cli and git installed and available on your path.

If you don't have those, consider visiting their respective websites and setting up those tools - they are both excellent and fundamental to the scala ecosystem, you'll need them at some point ...

- [coursier](https://get-coursier.io/docs/cli-installation)
- [scala-cli](https://scala-cli.virtuslab.org)

