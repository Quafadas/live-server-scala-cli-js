import cats.effect.kernel.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.*
import io.circe.yaml.parser
import cats.data.NonEmptyList

object ProxyConfig:

  def loadYaml[F[_]: Async](content: String): F[Equilibrium] =
    parser.parse(content).liftTo[F].flatMap(_.as[Equilibrium].liftTo[F])

  case class Equilibrium(
      http: Http
  )
  object Equilibrium:
    implicit val decoder: Decoder[Equilibrium] = new Decoder[Equilibrium]:
      def apply(c: HCursor): Decoder.Result[Equilibrium] = for http <- c.downField("http").as[Http]
      yield Equilibrium(http)
  end Equilibrium

  sealed trait LocationMatcher
  object LocationMatcher:
    case class Exact(value: String) extends LocationMatcher
    case class Prefix(value: String) extends LocationMatcher

    sealed trait ModifierSymbol
    object ModifierSymbol:
      case object Exact extends ModifierSymbol
      case object Prefix extends ModifierSymbol

      implicit val decoder: Decoder[ModifierSymbol] = Decoder[String].emap {
        case "=" => Exact.asRight
        case _   => "Invalid Modifier".asLeft
      }
    end ModifierSymbol

    // Add Regex Support via dynamapath
    // case object CaseSensitiveRegex(d: Path) extends LocationModifier // TODO Add CaseSenstive Option
  end LocationMatcher

  case class Location(
      // key modifier defaults to Prefix, can override to =
      matcher: LocationMatcher,
      proxyPass: String // Has Our variables
  )
  object Location:
    def decoder(
        upstreams: Map[String, Upstream]
    ) = new Decoder[Location]:
      def apply(c: HCursor): Decoder.Result[Location] = for
        modifier <- c
          .downField("modifier")
          .as[Option[LocationMatcher.ModifierSymbol]]
          .map(_.getOrElse(LocationMatcher.ModifierSymbol.Prefix))
        out <- modifier match
          case LocationMatcher.ModifierSymbol.Exact =>
            c.downField("matcher").as[String].map(LocationMatcher.Exact(_))
          case LocationMatcher.ModifierSymbol.Prefix =>
            c.downField("matcher").as[String].map(LocationMatcher.Prefix(_))
        // TODO support all positions not only tail position
        proxy <- c
          .downField("proxyPass")
          .as[String](Decoder[String].emap { s =>
            if !s.contains("$") then s.asRight
            else
              s.split('$')
                .lastOption
                .toRight("Value doesnt exist in split")
                .flatMap { variable =>
                  if upstreams.contains(variable) then s.asRight
                  else s"Variable $variable not present in upstreams".asLeft
                }

          })
      yield Location(out, proxy)
  end Location

  case class Server(
      listen: Port,
      serverNames: List[String],
      locations: List[Location]
  )
  object Server:
    def decoder(
        upstreams: Map[String, Upstream]
    ) = new Decoder[Server]:
      def apply(c: HCursor): Decoder.Result[Server] = for
        listen <-
          if !c.downField("listen").failed then
            c.downField("listen").as[Int].flatMap {
              Port.fromInt(_).toRight(DecodingFailure("Invalid Port", List.empty))
            }
          else port"8080".asRight
        names <- c.downField("serverNames").as[List[String]]
        locations <- c.downField("locations").as[List[Location]](Decoder.decodeList(Location.decoder(upstreams)))
      yield Server(listen, names, locations)
  end Server

  case class UpstreamServer(
      host: Host,
      port: Port,
      weight: Int
  )
  object UpstreamServer:
    implicit val decoder: Decoder[UpstreamServer] = new Decoder[UpstreamServer]:
      def apply(c: HCursor): Decoder.Result[UpstreamServer] = for
        host <- c
          .downField("host")
          .as[String]
          .flatMap(s => Host.fromString(s).toRight(DecodingFailure("Invalid Host", List.empty)))
        port <- c
          .downField("port")
          .as[Int]
          .flatMap(i => Port.fromInt(i).toRight(DecodingFailure("Invalid Port", List.empty)))
        weight <- c.downField("weight").as[Option[Int]].map(_.getOrElse(1))
      yield UpstreamServer(host, port, weight)
  end UpstreamServer
  case class Upstream(
      name: String,
      servers: NonEmptyList[UpstreamServer]
  )
  object Upstream:
    implicit val decoder: Decoder[Upstream] = new Decoder[Upstream]:
      def apply(c: HCursor): Decoder.Result[Upstream] = (
        c.downField("name").as[String],
        c.downField("servers").as[NonEmptyList[UpstreamServer]]
      ).mapN(Upstream(_, _))
  end Upstream

  case class Http(
      servers: NonEmptyList[Server],
      upstreams: List[Upstream]
  )
  object Http:
    implicit val decoder: Decoder[Http] = new Decoder[Http]:
      def apply(c: HCursor): Decoder.Result[Http] = for
        upstreams <- c.downField("upstreams").as[List[Upstream]]
        map = upstreams.groupBy(_.name).map(t => t._1 -> t._2.head).toMap
        servers <- c.downField("servers").as[NonEmptyList[Server]](Decoder.decodeNonEmptyList(Server.decoder(map)))
      yield Http(servers, upstreams)
  end Http
end ProxyConfig
