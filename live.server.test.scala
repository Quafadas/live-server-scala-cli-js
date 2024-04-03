import scala.compiletime.uninitialized
import com.microsoft.playwright.*
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import com.microsoft.playwright.Page.InputValueOptions
import java.nio.file.Paths
import com.microsoft.playwright.impl.driver.Driver
import scala.concurrent.Future

import cats.effect.unsafe.implicits.global

/*
Run
cs launch com.microsoft.playwright:playwright:1.41.1 -M "com.microsoft.playwright.CLI" -- install --with-deps
before this test, to make sure that the driver bundles are downloaded.
 */
class PlaywrightTest extends munit.FunSuite:

  val port = 8080
  var pw: Playwright = uninitialized
  var browser: Browser = uninitialized
  var page: Page = uninitialized

  val testDir = os.pwd / "testDir"
  val outDir = testDir / ".out"

  override def beforeAll(): Unit =

    pw = Playwright.create()
    browser = pw.chromium().launch();
    page = browser.newPage();
  end beforeAll

  test("incremental") {

    if os.exists(testDir) then os.remove.all(os.pwd / "testDir")
    os.makeDir.all(outDir)

    os.write.over(testDir / "hello.scala", helloWorldCode("Hello"))
    os.write.over(testDir / ".out" / "styles.less", "")

    LiveServer
      .run(
        List(
          testDir.toString,
          outDir.toString
        )
      )
      .unsafeToFuture()

    Thread.sleep(5000) // give the thing time to start.

    page.navigate(s"http://localhost:8085/index.html")
    assertThat(page.locator("h1")).containsText("HelloWorld");

    os.write.over(testDir / "hello.scala", helloWorldCode("Bye"))
    assertThat(page.locator("h1")).containsText("ByeWorld");

    os.write.append(testDir / ".out" / "styles.less", "h1 { color: red; }")
    assertThat(page.locator("h1")).hasCSS("color", "rgb(255, 0, 0)")

  }

  override def afterAll(): Unit =
    super.afterAll()
    pw.close()
    // os.remove.all(testDir)

  end afterAll

end PlaywrightTest

def helloWorldCode(greet: String) = s"""
//> using scala 3.3.3
//> using platform js

//> using dep org.scala-js::scalajs-dom::2.8.0
//> using dep com.raquo::laminar::17.0.0-M6

//> using jsModuleKind es
//> using jsModuleSplitStyleStr smallmodulesfor
//> using jsSmallModuleForPackage webapp

package webapp

import org.scalajs.dom
import org.scalajs.dom.document
import com.raquo.laminar.api.L.{*, given}

@main
def main: Unit =
  renderOnDomContentLoaded(
    dom.document.getElementById("app"),
    interactiveApp
  )

def interactiveApp =
  val hiVar = Var("World")
  div(
    h1(
      "$greet",
      child.text <-- hiVar.signal
    ),
    p("This is a simple example of a Laminar app."),
    // https://demo.laminar.dev/app/form/controlled-inputs
    input(
      typ := "text",
      controlled(
        value <-- hiVar.signal,
        onInput.mapToValue --> hiVar.writer
      )
    )
  )
"""
