package io.github.quafadas.sjsls

import com.comcast.ip4s.Port
import com.monovore.decline.Opts

private[sjsls] object CliOps:
  val logLevelOpt: Opts[String] = Opts
    .option[String]("log-level", help = "The log level. info, debug, error, trace)")
    .withDefault("info")
    .validate("Invalid log level") {
      case "info"  => true
      case "debug" => true
      case "error" => true
      case "warn"  => true
      case "trace" => true
      case _       => false
    }

  val openBrowserAtOpt =
    Opts
      .option[String](
        "browse-on-open-at",
        "A suffix to localhost where we'll open a browser window on server start - e.g. /ui/greatPage OR just `/` for root "
      )
      .withDefault("/")

  val baseDirOpt =
    Opts
      .option[String]("project-dir", "The fully qualified location of your project - e.g. c:/temp/helloScalaJS")
      .orNone

  val outDirOpt = Opts
    .option[String](
      "out-dir",
      "Where the compiled JS will be compiled to - e.g. c:/temp/helloScalaJS/.out. If no file is given, a temporary directory is created."
    )
    .orNone

  val portOpt = Opts
    .option[Int]("port", "The port you want to run the server on - e.g. 3000")
    .withDefault(3000)
    .validate("Port must be between 1 and 65535")(i => i > 0 && i < 65535)
    .map(i => Port.fromInt(i).get)

  val proxyPortTargetOpt = Opts
    .option[Int]("proxy-target-port", "The port you want to forward api requests to - e.g. 8080")
    .orNone
    .validate("Proxy Port must be between 1 and 65535")(iOpt => iOpt.fold(true)(i => i > 0 && i < 65535))
    .map(i => i.flatMap(Port.fromInt))

  val proxyPathMatchPrefixOpt = Opts
    .option[String]("proxy-prefix-path", "Match routes starting with this prefix - e.g. /api")
    .orNone

  val clientRoutingPrefixOpt = Opts
    .option[String](
      "client-routes-prefix",
      "Routes starting with this prefix  e.g. /app will return index.html. This enables client side routing via e.g. waypoint"
    )
    .orNone

  val buildToolOpt = Opts
    .option[String]("build-tool", "scala-cli or mill")
    .validate("Invalid build tool") {
      case "scala-cli" => true
      case "mill"      => true
      case "none"      => true
      case _           => false
    }
    .withDefault("scala-cli")
    .map {
      _ match
        case "scala-cli" => ScalaCli()
        case "mill"      => Mill()
        case "none"      => NoBuildTool()
    }

  val injectPreloadsOpt = Opts
    .flag(
      "inject-preloads",
      "Whether or not to attempt injecting module preloads into the index.html, potentially speeds up page load, but may not work with all servers and or cause instability in the refresh process."
    )
    .orFalse

  val extraBuildArgsOpt: Opts[List[String]] = Opts
    .options[String](
      "extra-build-args",
      "Extra arguments to pass to the build tool"
    )
    .orEmpty

  val stylesDirOpt: Opts[Option[String]] = Opts
    .option[String](
      "styles-dir",
      "A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles"
    )
    .orNone

  val indexHtmlTemplateOpt: Opts[Option[String]] = Opts
    .option[String](
      "path-to-index-html",
      "a path to a directory which contains index.html. The entire directory will be served as static assets"
    )
    .orNone

  val preventBrowserOpenOpt = Opts
    .flag(
      "prevent-browser-open",
      "prevent the browser from opening on server start"
    )
    .orFalse

  val millModuleNameOpt: Opts[Option[String]] = Opts
    .option[String](
      "mill-module-name",
      "Extra arguments to pass to the build tool"
    )
    .validate("mill module name cannot be blank") {
      case "" => false
      case _  => true
    }
    .orNone

  val buildToolInvocation: Opts[Option[String]] = Opts
    .option[String](
      "build-tool-invocation",
      "This string will be passed to an fs2 process which invokes the build tool. By default it's 'scala-cli', or `mill`, " +
        "and is assumed is on the path"
    )
    .orNone

  val dezombifyOpt = Opts
    .flag(
      "dezombify",
      "Whether or not to attempt killing any processes that are using the specified port. Default: true"
    )
    .orTrue
end CliOps
