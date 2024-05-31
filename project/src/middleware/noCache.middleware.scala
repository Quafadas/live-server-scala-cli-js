import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.typelevel.ci.CIStringSyntax

import cats.data.Kleisli
import cats.effect.*
import cats.effect.IO
import scribe.Scribe
import cats.data.OptionT
import cats.syntax.all.*

object NoCacheMiddlware:

  def apply(service: HttpRoutes[IO])(logger: Scribe[IO]): HttpRoutes[IO] = Kleisli {
    (req: Request[IO]) =>
      OptionT.liftF(logger.trace("No cache middleware")) >>
        OptionT.liftF(logger.trace(req.toString)) >>
        service(req).map {
          resp =>
            resp.putHeaders(
              Header.Raw(ci"Cache-Control", "no-cache")
            )
        }
  }

end NoCacheMiddlware
