package io.github.quafadas.sjsls

sealed trait BuildTool(val invokedVia: String)
class ScalaCli
    extends BuildTool(
      if isWindows then "scala-cli.bat" else "scala-cli"
    )
class Mill
    extends BuildTool(
      if isWindows then "mill.bat" else "mill"
    )

class NoBuildTool extends BuildTool("")
