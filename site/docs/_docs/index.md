# Scala JS live server

> Show me scala JS - I have 20 seconds.

Paste this into your terminal and hit enter.

```sh

scala-cli --version && \
cs version && \
git clone https://github.com/Quafadas/viteless.git && \
cd viteless && \
cs launch io.github.quafadas::sjsls:{{projectVersion}}
```
Note that to work, you need the following directives in scala-cli:

```scala
//> using scala 3.7.0
//> using platform js
//> using jsModuleKind es
//> using jsEmitSourceMaps true
//> using jsModuleSplitStyleStr smallmodulesfor
//> using jsSmallModuleForPackage boid

```


## It worked... okay... I have 20 more seconds

Edit `hello.scala` and save the change. You should see the change refreshed in your browser.




## Aw shoot - errors

The command above assumes you have coursier (as cs) and scala-cli installed and available on your path.

If you don't have those, consider visiting their respective websites and setting up those tools - they are both excellent and fundamental to the scala ecosystem, you'll need them at some point ...

- [coursier](https://get-coursier.io/docs/cli-installation)
- [scala-cli](https://scala-cli.virtuslab.org)

Once installed, give it another go.