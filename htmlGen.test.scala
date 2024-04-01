import fs2.io.file.Path

class HtmlGenTest extends munit.FunSuite:
  test("makeHeader should return the correct header") {
    val modules = List(
      (Path("/path/to/module1"), "1h")
    )

    val expectedHeader =
      """<html>
<head>
<link rel="modulepreload" src="/path/to/module1?hash=1h"/>
</head>
<body>
<div id="app"></div>
</body>
</html>""".replaceAll("\\s", "")

    val actualHeader = makeHeader(modules)

    println(expectedHeader.replaceAll("\\s", ""))
    println(actualHeader.render.replaceAll("\\s", ""))

    assert(actualHeader.render.replaceAll("\\s", "") == expectedHeader)
  }
end HtmlGenTest
