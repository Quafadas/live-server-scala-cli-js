package io.github.quafadas.sjsls

import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.Status

import cats.effect.IO

import munit.CatsEffectSuite

class BuildRoutesSuite extends CatsEffectSuite:

  def makeReq(s: String) = org.http4s.Request[IO](uri = org.http4s.Uri.unsafeFromString(s))

  // def simpleResponse(body: String) = HttpRoutes.of[IO] {
  //   case _ => OptionT(IO(Option(Response[IO]().withEntity(body).withStatus(Status.Ok))))
  // }

  test("if no refresh route is given we get not found") {

    val routes = frontendRoutes[IO](
      clientSpaRoutes = None,
      staticAssetRoutes = None,
      appRoutes = None
    )

    val status = routes(makeReq("anything")).map(_.status).getOrElse(Status.NotFound)

    assertIO(status, Status.NotFound)
  }

  test("spa routes are found, i.e. the Router behaves as expected") {

    val routes = frontendRoutes(
      clientSpaRoutes = Some(
        "spa",
        HttpRoutes.of[IO](_ => IO(Response[IO]().withStatus(Status.Ok).withEntity("spaRoute")))
      ),
      staticAssetRoutes = None,
      appRoutes = None
    )

    val status1 = routes(makeReq("spa")).map(_.status).getOrElse(Status.NotFound)
    val status2 = routes(makeReq("/spa")).map(_.status).getOrElse(Status.NotFound)
    val status3 = routes(makeReq("spa/something")).map(_.status).getOrElse(Status.NotFound)
    val status4 = routes(makeReq("/spa/something?wwaaaaa")).map(_.status).getOrElse(Status.NotFound)
    val status5 = routes(makeReq("nope")).map(_.status).getOrElse(Status.NotFound)

    val checkEntity = routes(makeReq("spa")).map(_.bodyText.compile.string).getOrElse(IO("nope")).flatten

    assertIO(status1, Status.Ok) >>
      assertIO(status2, Status.Ok) >>
      assertIO(status3, Status.Ok) >>
      assertIO(status4, Status.Ok) >>
      assertIO(status5, Status.NotFound) >>
      assertIO(checkEntity, "spaRoute")

  }

  test("appRouteInMemory serves JS from lookup") {
    val jsContent = "console.log('hello');".getBytes("UTF-8")
    val lookup: String => Option[Array[Byte]] = {
      case "main.abc12345.js" => Some(jsContent)
      case _                  => None
    }
    val route = appRouteInMemory[IO](lookup)

    val found = route(makeReq("/main.abc12345.js")).map(_.status).getOrElse(Status.NotFound)
    val notFound = route(makeReq("/missing.js")).map(_.status).getOrElse(Status.NotFound)
    val body = route(makeReq("/main.abc12345.js"))
      .semiflatMap(_.body.compile.to(Array))
      .getOrElse(Array.empty[Byte])

    assertIO(found, Status.Ok) >>
      assertIO(notFound, Status.NotFound) >>
      assertIO(body.map(_.toSeq), jsContent.toSeq)
  }

  test("appRouteInMemory serves WASM from lookup") {
    val wasmContent = Array[Byte](0, 97, 115, 109) // fake wasm magic bytes
    val lookup: String => Option[Array[Byte]] = {
      case "module.abc12345.wasm" => Some(wasmContent)
      case _                     => None
    }
    val route = appRouteInMemory[IO](lookup)

    val found = route(makeReq("/module.abc12345.wasm")).map(_.status).getOrElse(Status.NotFound)
    val notFound = route(makeReq("/missing.wasm")).map(_.status).getOrElse(Status.NotFound)

    assertIO(found, Status.Ok) >>
      assertIO(notFound, Status.NotFound)
  }

  test("appRouteInMemory serves source maps from lookup") {
    val mapContent = """{"version":3}""".getBytes("UTF-8")
    val lookup: String => Option[Array[Byte]] = {
      case "main.abc12345.js.map" => Some(mapContent)
      case _                      => None
    }
    val route = appRouteInMemory[IO](lookup)

    val found = route(makeReq("/main.abc12345.js.map")).map(_.status).getOrElse(Status.NotFound)
    val notFound = route(makeReq("/other.map")).map(_.status).getOrElse(Status.NotFound)

    assertIO(found, Status.Ok) >>
      assertIO(notFound, Status.NotFound)
  }

  test("appRouteInMemory does not match non-JS/WASM/map extensions") {
    val lookup: String => Option[Array[Byte]] = _ => Some(Array.empty[Byte])
    val route = appRouteInMemory[IO](lookup)

    val html = route(makeReq("/index.html")).map(_.status).getOrElse(Status.NotFound)
    val css = route(makeReq("/styles.css")).map(_.status).getOrElse(Status.NotFound)

    assertIO(html, Status.NotFound) >>
      assertIO(css, Status.NotFound)
  }

end BuildRoutesSuite
