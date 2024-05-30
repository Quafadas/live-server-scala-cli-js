import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.typelevel.ci.CIStringSyntax

import cats.data.Kleisli
import cats.effect.*
import cats.effect.IO

object NoCacheMiddlware:

  def apply(service: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>
      service(req).map {
        resp =>
          resp.putHeaders(
            Header.Raw(ci"Cache-Control", "no-cache")
          )
      }
  }

end NoCacheMiddlware
