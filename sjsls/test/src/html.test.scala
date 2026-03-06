package io.github.quafadas.sjsls

class HtmlGenTests extends munit.FunSuite {

  test("That we can generate a basic html page") {
    val html = vanillaTemplate(true, true, Some("main.js"), None)
    println(html)
    assert(html.contains("<html>"))
    assert(html.contains("</head>"))
    assert(html.contains("</body>"))
    assert(html.contains("main.js"))
    assert(html.contains("index.less"))
    assert(html.contains("less.watch()"))
    assert(html.contains("</html>"))
  }

  test("That we can generate a basic html page") {
    val html = vanillaTemplate(true, false, Some("main.js"), None)
    assert(!html.contains("less.watch()"))
  }


}