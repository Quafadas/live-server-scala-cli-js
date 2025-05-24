package io.github.quafadas.sjsls

import cats.effect.IO
import cats.effect.kernel.Ref

import munit.CatsEffectSuite

class UtilityFcs extends CatsEffectSuite:

  test("That we actually inject the preloads ") {

    val html = makeHeader(
      modules = Seq(
        (fs2.io.file.Path("main.js"), "hash")
      ),
      withStyles = false,
      attemptPreload = true
    )

    assert(html.render.contains("modulepreload"))
    assert(html.render.contains("hash"))

  }

  ResourceFunFixture {
    for
      ref <- Ref.of[IO, Map[String, String]](Map.empty).toResource
      _ <- ref.update(_.updated("internal.js", "hash")).toResource
    yield ref
  }.test("That we can make internal preloads") {
    ref =>
      val html = injectModulePreloads(ref, "<html><head></head><body></body></html>")
      html.map: html =>
        assert(html.contains("modulepreload"))
        assert(html.contains("internal.js"))
  }

  test(" That we can inject a refresh script") {
    val html = injectRefreshScript("<html><head></head><body></body></html>")
    assert(
      html.contains("sse.addEventListener")
    )

    assert(
      html.contains("""location.reload()});</script>
</body></html>""")
    )
  }

end UtilityFcs
