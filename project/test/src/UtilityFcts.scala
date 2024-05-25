class UtilityFcs extends munit.FunSuite:

  test("That we actually inject the preloads ") {

    val html = makeHeader(
      modules = Seq(
        (fs2.io.file.Path("main.js"), "hash")
      ),
      withStyles = false
    )

    assert(html.render.contains("modulepreload"))
    assert(html.render.contains("hash"))

  }

  test(" That we can inject preloads into a template") {
    val html = injectModulePreloads(
      modules = Seq(
        (fs2.io.file.Path("main.js"), "hash")
      ),
      template = "<html><head></head></html>"
    )
    assert(html.contains("hash"))
    assertEquals(
      html,
      """<html><head>
<link rel="modulepreload" href="main.js?hash=hash" />
</head></html>"""
    )
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
