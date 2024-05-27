import com.comcast.ip4s.*

import cats.data.NonEmptyList

import cats.syntax.all.*

object ProxyConfig:

  case class Equilibrium(
      http: HttpProxyConfig
  )
  sealed trait LocationMatcher
  object LocationMatcher:
    case class Exact(value: String) extends LocationMatcher
    case class Prefix(value: String) extends LocationMatcher

    sealed trait ModifierSymbol
    object ModifierSymbol:
      case object Exact extends ModifierSymbol
      case object Prefix extends ModifierSymbol
    end ModifierSymbol
  end LocationMatcher

  case class Location(
      // key modifier defaults to Prefix, can override to =
      matcher: LocationMatcher,
      proxyPass: String // Has Our variables
  )

  case class Server(
      listen: Port,
      serverNames: List[String],
      locations: List[Location]
  )
  case class UpstreamServer(
      host: Host,
      port: Port,
      weight: Int
  )
  case class Upstream(
      name: String,
      servers: NonEmptyList[UpstreamServer]
  )

  case class HttpProxyConfig(
      servers: NonEmptyList[Server],
      upstreams: List[Upstream]
  )

end ProxyConfig
