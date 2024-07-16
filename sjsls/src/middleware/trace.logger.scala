package io.github.quafadas.sjsls

import org.http4s.server.middleware.Logger
import cats.effect.IO
import scribe.Scribe

def traceLoggerMiddleware(logger: Scribe[IO]) = Logger.httpRoutes[IO](
  logHeaders = true,
  logBody = true,
  redactHeadersWhen = _ => false,
  logAction = Some((msg: String) => logger.trace(msg))
)
