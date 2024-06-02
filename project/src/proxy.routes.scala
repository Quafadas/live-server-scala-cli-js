import org.http4s.HttpRoutes
import org.http4s.client.Client

import scribe.Scribe

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Random

import ProxyConfig.Equilibrium

def makeProxyRoutes(
    client: Client[IO],
    pathPrefix: Option[String],
    proxyConfig: Resource[IO, Option[Equilibrium]]
)(logger: Scribe[IO]): Resource[IO, HttpRoutes[IO]] =
  proxyConfig.flatMap {
    case Some(pc) =>
      {
        given R: Random[IO] = Random.javaUtilConcurrentThreadLocalRandom[IO]
        logger.debug("setup proxy server") >>
          IO(HttpProxy.servers[IO](pc, client, pathPrefix.getOrElse(???)).head._2)
      }.toResource

    case None =>
      (
        logger.debug("no proxy set") >>
          IO(HttpRoutes.empty[IO])
      ).toResource
  }

end makeProxyRoutes
