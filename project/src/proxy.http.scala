import cats.*
import cats.syntax.all.*
import cats.data.*
import cats.effect.kernel.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.{Host, `X-Forwarded-For`}
import com.comcast.ip4s.Port
import ProxyConfig.Location
import cats.effect.std.Random

object HttpProxy:

  def servers[F[_]: MonadCancelThrow: Random](
      c: ProxyConfig.Equilibrium,
      client: Client[F]
  ): NonEmptyMap[Port, HttpRoutes[F]] =
    val upstreams = c.http.upstreams.groupMapReduce(_.name)(_.servers) { case (a, b) => a.concatNel(b) }
    c.http.servers
      .groupByNem(server => server.listen)
      .map { servers =>

        val routes: HttpRoutes[F] = HttpRoutes.of { case (req: Request[F]) =>
          val pathRendered = req.uri.path.renderString
          val host = req.headers.get[Host].map(_.host).getOrElse("") // Host set otherwise empty string
          val newServers = servers.filter(_.serverNames.contains(host))

          val exact =
            newServers.flatMap(_.locations).collect { case Location(out: ProxyConfig.LocationMatcher.Exact, proxy) =>
              (out, proxy)
            }

          val proxy = exact
            .collectFirst { case (e, p) if e.value === pathRendered => p }
            .orElse {
              val prefix =
                newServers.flatMap(_.locations).collect {
                  case Location(out: ProxyConfig.LocationMatcher.Prefix, proxy) =>
                    (out, proxy)
                }
              prefix
                .collect { case (e, p) if pathRendered.startsWith(e.value) => (e, p) }
                .sortBy { case ((p, _)) => p.value.length() }
                .headOption
                .map(_._2)
            }

          proxy.fold(
            Response[F](Status.NotFound).withEntity("No Route Found").pure[F]
          )(
            proxyThrough[F](_, upstreams)
              .flatMap(uri => client.toHttpApp(req.removeHeader[Host].withUri(uri.addPath(pathRendered))))
          )
        }

        xForwardedMiddleware(routes)
      }
  end servers

  private def proxyThrough[F[_]: Random: MonadThrow](
      proxyPass: String,
      upstreams: Map[String, NonEmptyList[ProxyConfig.UpstreamServer]]
  ): F[Uri] =
    if !proxyPass.contains("$") then Uri.fromString(proxyPass).liftTo[F]
    else
      extractVariable(proxyPass).flatMap { case (before, variable, after) =>
        upstreams
          .get(variable)
          .fold(throw new RuntimeException("Variable Not Found In Upstreams"))(nel =>
            pickUpstream[F](nel)
              .flatMap(us => Uri.fromString(before ++ us.host.toString ++ ":" ++ us.port.toString ++ after).liftTo[F])
          )
      }

  private def extractVariable[F[_]: ApplicativeThrow](s: String): F[(String, String, String)] =
    s.split('$').toList match
      case before :: after :: _ =>
        val i = after.indexOf("/")
        if i < 0 then (before, after, "").pure[F]
        else
          after.split("/").toList match
            case variable :: after :: _ => (before, variable, after).pure[F]
            case _                      => new RuntimeException("Split on / failed in extract Variable").raiseError
        end if
      case _ => new RuntimeException("Split on $ failed in extractVariable").raiseError

  private def pickUpstream[F[_]: Random: Monad](
      upstreams: NonEmptyList[ProxyConfig.UpstreamServer]
  ): F[ProxyConfig.UpstreamServer] =
    randomWeighted(upstreams.map(a => (a.weight, a)))

  private def randomWeighted[F[_]: Random: Monad, A](weighted: NonEmptyList[(Int, A)]): F[A] =
    val max = weighted.foldMap(_._1)
    def go: F[Option[A]] = Random[F].betweenInt(0, max).map { i =>
      var running: Int = i
      weighted.collectFirstSome { case (weight, a) =>
        if running < weight then Some(a)
        else
          running -= weight
          None
      }
    }
    def f: F[A] = go.flatMap {
      case None    => f
      case Some(a) => a.pure[F]
    }
    f
  end randomWeighted

  def xForwardedMiddleware[G[_], F[_]](http: Http[G, F]): Http[G, F] = Kleisli { (req: Request[F]) =>
    req.remote.fold(http.run(req)) { remote =>
      val forwardedFor = req.headers
        .get[`X-Forwarded-For`]
        .fold(`X-Forwarded-For`(NonEmptyList.of(Some(remote.host))))(init =>
          `X-Forwarded-For`(init.values :+ remote.host.some)
        )
      val forwardedProtocol =
        req.uri.scheme.map(headers.`X-Forwarded-Proto`(_))

      val forwardedHost = req.headers.get[Host].map(host => "X-Forwarded-Host" -> Host.headerInstance.value(host))

      val init = req.putHeaders(forwardedFor)

      val second = forwardedProtocol.fold(init)(proto => init.putHeaders(proto))
      val third = forwardedHost.fold(second)(host => second.putHeaders(host))
      http.run(third)
    }
  }
end HttpProxy
