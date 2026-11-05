package mill.scalajslib.config

import mill.api.PathRef
import mill.api.TaskCtx
import mill.api.daemon.Result
import mill.scalajslib.api
import mill.scalajslib.config.worker.ScalaJSConfigWorker
import org.scalajs.linker.interface as sjs

/** Makes a link task's result reflect linker output that isn't written to `Task.dest`.
  *
  * With a `customLinkerOutputDir`, Mill's link task returns the module names plus an empty `Task.dest`. That result is
  * the same on every build, so Mill treats every task after the link (content hashing, `index.html`, the page refresh)
  * as cached and they never see the new output. Writing [[linkerOutputDigest]] into `Task.dest` makes the result change
  * whenever the output does.
  *
  * This lives in Mill's package because `linkJs` is `private[scalajslib]`. A Mill upgrade that changes its signature
  * fails to compile here, rather than silently bringing the stale-build bug back.
  */
trait LinkerOutputDigest extends ScalaJSConfigModule:

  /** A digest of the output the linker has just written, or `None` when it writes to `Task.dest` as usual. */
  protected def linkerOutputDigest: Option[String] = None

  override private[scalajslib] def linkJs(
      worker: ScalaJSConfigWorker,
      toolsClasspath: Seq[PathRef],
      runClasspath: Seq[PathRef],
      moduleInitializers: Seq[sjs.ModuleInitializer],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      importMap: Seq[api.ESModuleImportMapping],
      config: sjs.StandardConfig
  )(using ctx: TaskCtx): Result[api.Report] =
    super
      .linkJs(worker, toolsClasspath, runClasspath, moduleInitializers, forceOutJs, testBridgeInit, importMap, config)
      .map {
        report =>
          linkerOutputDigest match
            case None         => report
            case Some(digest) =>
              os.write.over(ctx.dest / "linker-output.sha256", digest)
              api.Report(report.publicModules, PathRef(ctx.dest))
      }
end LinkerOutputDigest
