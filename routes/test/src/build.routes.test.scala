package io.github.quafadas.sjsls

import munit.CatsEffectSuite
import cats.effect.IO
import org.http4s.server.Router
import org.http4s.HttpRoutes
import org.http4s.Status
import org.http4s.Response
import cats.data.OptionT

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

end BuildRoutesSuite
