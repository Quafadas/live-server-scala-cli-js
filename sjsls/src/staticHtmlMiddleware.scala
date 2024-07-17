package io.github.quafadas.sjsls

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.typelevel.ci.CIStringSyntax

import scribe.Scribe

import cats.data.Kleisli
import cats.effect.IO

object StaticHtmlMiddleware:
  def apply(service: HttpRoutes[IO], injectStyles: Boolean, zdt: ZonedDateTime)(logger: Scribe[IO]): HttpRoutes[IO] =
    Kleisli {
      (req: Request[IO]) =>
        service(req).semiflatMap(userBrowserCacheHeaders(_, zdt, injectStyles))
    }

end StaticHtmlMiddleware

def userBrowserCacheHeaders(resp: Response[IO], lastModZdt: ZonedDateTime, injectStyles: Boolean) =
  val hash = resp.body.through(fs2.hash.md5).through(fs2.text.hex.encode).compile.string
  hash.map: h =>
    resp.putHeaders(
      Header.Raw(ci"Cache-Control", "no-cache"),
      Header.Raw(
        ci"ETag",
        h
      ),
      Header.Raw(
        ci"Last-Modified",
        formatter.format(lastModZdt)
      ),
      Header.Raw(
        ci"Expires",
        httpCacheFormat(ZonedDateTime.ofInstant(Instant.now().plusSeconds(10000000), ZoneId.of("GMT")))
      )
    )
end userBrowserCacheHeaders
