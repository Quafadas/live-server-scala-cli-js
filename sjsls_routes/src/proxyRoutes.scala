package io.github.quafadas.sjsls

import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.server.Router
import org.http4s.server.middleware.Logger

import com.comcast.ip4s.Host
import com.comcast.ip4s.Port

import scribe.Scribe

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Random
import cats.syntax.all.*

import io.github.quafadas.sjsls.ProxyConfig.Equilibrium
import io.github.quafadas.sjsls.ProxyConfig.LocationMatcher
import io.github.quafadas.sjsls.ProxyConfig.Server

def makeProxyRoutes(
    client: Client[IO],
    proxyConfig: Option[(Equilibrium, String)]
)(logger: Scribe[IO]): HttpRoutes[IO] =
  proxyConfig match
    case Some((pc, pathPrefix)) =>
      given R: Random[IO] = Random.javaUtilConcurrentThreadLocalRandom[IO]
      Logger.httpRoutes[IO](
        logHeaders = true,
        logBody = true,
        redactHeadersWhen = _ => false,
        logAction = Some((msg: String) => logger.trace(msg))
      )(
        Router(
          pathPrefix -> HttpProxy.servers[IO](pc, client, pathPrefix).head._2
        )
      )

    case None =>
      HttpRoutes.empty[IO]

end makeProxyRoutes

def proxyConf(proxyTarget: Option[Port], pathPrefix: Option[String]): Resource[IO, Option[(Equilibrium, String)]] =
  proxyTarget
    .zip(pathPrefix)
    .traverse {
      (pt, prfx) =>
        IO(
          (
            Equilibrium(
              ProxyConfig.HttpProxyConfig(
                servers = NonEmptyList(
                  Server(
                    listen = pt,
                    serverNames = List("localhost"),
                    locations = List(
                      ProxyConfig.Location(
                        matcher = LocationMatcher.Prefix(prfx),
                        proxyPass = "http://$backend"
                      )
                    )
                  ),
                  List()
                ),
                upstreams = List(
                  ProxyConfig.Upstream(
                    name = "backend",
                    servers = NonEmptyList(
                      ProxyConfig.UpstreamServer(
                        host = Host.fromString("localhost").get,
                        port = pt,
                        weight = 5
                      ),
                      List()
                    )
                  )
                )
              )
            ),
            prfx
          )
        )

    }
    .toResource
