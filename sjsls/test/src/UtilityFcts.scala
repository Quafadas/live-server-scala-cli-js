package io.github.quafadas.sjsls

import cats.effect.IO
import cats.effect.kernel.Ref

import munit.CatsEffectSuite

class UtilityFcs extends CatsEffectSuite:

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

  ResourceFunFixture {
    Ref.of[IO, Map[String, String]](Map("internal.js" -> "hash")).toResource
  }.test("A page without a </head> is left as is rather than failing") {
    ref =>
      val page = "<html><body></body></html>"
      assertIO(injectModulePreloads(ref, page), page)
  }

end UtilityFcs
