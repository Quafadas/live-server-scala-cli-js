package io.github.quafadas.sjsls

import cats.effect.kernel.Ref
import fs2.concurrent.Topic
import fs2.io.file.Files
import org.http4s.HttpRoutes
import org.http4s

import cats.MonadThrow
import cats.effect.IO
import scribe.Scribe
import cats.effect.kernel.Resource
import org.http4s.server.Router
import cats.syntax.all.*

import cats.Monad

/** This is a helper function which would allow you to construct a Router which mimics the behaviour of the "live
  * server".
  *
  * The idea is that you .combineK() with the other routes in your backend. These routes mimic the routing behaviour of
  * the live server - i.e. make the frontend work - but without any messy middleware. This should provide a transparent
  * pathway to deployment. The routes have the following preference order;
  *
  *   - appRoutes
  *   - SPA
  *   - staticAssetRoutes
  *
  * @param clientSpaRoutes
  *   \- The frontend will be served below this route. It will normally return your index.html. For example, if you
  *   provide "spa" here, then all these requests; "/spa", "/spa/", "/spa/something", "/spa/something?query=string" will
  *   all return index.html.
  *
  * The browser relies on this behaviour to enable client side routing. Typically then, this parameter is something like
  *
  * Option(("ui", spaRoute[IO]("path/to/index.html")))
  *
  * @param staticAssetRoutes
  *   This will serve any static assets you have, at the root of the app. Typically, this is a simple http4s
  *   fileService. The fileRoute helper makes this simpler.
  *
  * e.g. ```fileRoute[IO]("path/to/static/assets")``` where that path includes something like `index.html` and
  * `main.css`.
  *
  * @param appRoutes
  *   This serves the javascript you have, at the root of the app. The entrpoint will normall be "main.js". Typically,
  *   this will be the output of your bundler. There is a helper function in the app.routes.scala.
  *
  * e.g. ```appRoute[IO]("path/to/javascript")```
  *
  * Where that path includes something like `main.js` and `main.js.map`. Usually, the outcome of the scala js linker.
  *
  * @param f
  * @return
  */
def buildRoutes(
    appRoutes: HttpRoutes[IO]
): HttpRoutes[IO] =
  // val allRoutes = appRoutes.map(r => ("", r)) ++ clientSpaRoutes ++ staticAssetRoutes.map(r => ("", r))
  // val tmp = f.pure(
  //   Router(allRoutes.toList*)
  // )

  // val spaRoutes2: HttpRoutes[F] = clientSpaRoutes.map(s => Router(s)).getOrElse(HttpRoutes.empty[F])
  // val staticAssetRoutes2: HttpRoutes[F] = staticAssetRoutes.getOrElse(HttpRoutes.empty[F])
  // val appRoutes2: HttpRoutes[F] = appRoutes.getOrElse(HttpRoutes.empty[F])

  // val routed: HttpRoutes[F] = appRoutes2.combineK(spaRoutes2).combineK(staticAssetRoutes2)

  appRoutes

end buildRoutes
