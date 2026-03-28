package io.github.quafadas.sjsls

import com.comcast.ip4s.Port

import fs2.concurrent.Topic

import cats.effect.IO

case class LiveServerConfig(
    baseDir: Option[String],
    outDir: Option[String] = None,
    port: Port,
    proxyPortTarget: Option[Port] = None,
    proxyPathMatchPrefix: Option[String] = None,
    clientRoutingPrefix: Option[String] = None,
    logLevel: String = "info",
    buildTool: BuildTool = ScalaCli(),
    openBrowserAt: String,
    preventBrowserOpen: Boolean = false,
    extraBuildArgs: List[String] = List.empty,
    millModuleName: Option[String] = None,
    stylesDir: Option[String] = None,
    indexHtmlTemplate: Option[String] = None,
    buildToolInvocation: Option[String] = None,
    injectPreloads: Boolean = false,
    dezombify: Boolean = true,
    logFile: Option[String] = None,
    customRefresh: Option[Topic[IO, Unit]] = None,
    customAssetRefresh: Option[Topic[IO, String]] = None,
    devToolsWorkspace: Option[(String, String)] = None,
    inMemoryFiles: Option[java.util.concurrent.ConcurrentHashMap[String, Array[Byte]]] = None
)
