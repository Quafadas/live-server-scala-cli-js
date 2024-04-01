
default:
  just --list

setupIde:
  scala-cli setup-ide .

package:
  scala-cli package .


test:
  scala-cli test project.scala htmlGen.scala htmlGen.test.scala

packageWatcher:
  scala-cli package file.nativewatcher.scala project.native.scala -f

serveNative:
  scala-cli run file.nativewatcher.scala project.native.scala

procT:
  scala-cli run process.scala project.scala

jvmWatch:
  scala-cli run project.scala file.watcher.scala

jvmServe:
  scala-cli run project.scala live.serverJvm.scala htmlGen.scala file.hasher.scala -- "/Users/simon/Code/helloScalaJs/out"