
default:
  just --list

setupIde:
  scala-cli setup-ide . --exclude testDir --exclude native

package:
  scala-cli package .

test:
  scala-cli test . --exclude testDir --exclude native --power

jvmWatch:
  scala-cli run project.scala file.watcher.scala

jvmServe:
  scala-cli run project.scala live.serverJvm.scala htmlGen.scala file.hasher.scala -- "/Users/simon/Code/helloScalaJs/out"

setupPlaywright:
  cs launch com.microsoft.playwright:playwright:1.41.1 -M "com.microsoft.playwright.CLI" -- install --with-deps

gha: setupPlaywright test
