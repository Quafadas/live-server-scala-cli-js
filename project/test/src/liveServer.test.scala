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
import scalatags.Text.styles
import cats.effect.IO

import com.comcast.ip4s.port
import com.comcast.ip4s.Port

import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.HttpRoutes
/*
Run
cs launch com.microsoft.playwright:playwright:1.41.1 -M "com.microsoft.playwright.CLI" -- install --with-deps
before this test, to make sure that the driver bundles are downloaded.
 */
class PlaywrightTest extends munit.FunSuite:

  var pw: Playwright = uninitialized
  var browser: Browser = uninitialized
  var page: Page = uninitialized

  val testDir = os.pwd / "testDir"
  val outDir = testDir / ".out"
  val styleDir = testDir / "styles"

  override def beforeAll(): Unit =
    pw = Playwright.create()
    browser = pw.chromium().launch();
    page = browser.newPage();
  end beforeAll

  // before each test, write in some simple code which should compile and some styles
  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(testDir) then os.remove.all(os.pwd / "testDir")
    end if
    os.makeDir.all(outDir)
    os.makeDir.all(styleDir)

    os.write.over(testDir / "hello.scala", helloWorldCode("Hello"))
    os.write.over(styleDir / "styles.less", "")
    os.proc("scala-cli", "compile", testDir.toString).call(cwd = testDir)
  end beforeEach

  test("incremental") {
    val thisTestPort = 3001

    if os.exists(testDir) then os.remove.all(os.pwd / "testDir")
    end if
    os.makeDir.all(outDir)
    os.makeDir.all(styleDir)

    os.write.over(testDir / "hello.scala", helloWorldCode("Hello"))
    os.write.over(styleDir / "styles.less", "")
    os.proc("scala-cli", "compile", testDir.toString).call(cwd = testDir)

    LiveServer
      .run(
        List(
          "--project-dir",
          testDir.toString,
          "--out-dir",
          outDir.toString,
          "--styles-dir",
          styleDir.toString,
          "--port",
          thisTestPort.toString
        )
      )
      .unsafeToFuture()

    Thread.sleep(4000) // give the thing time to start.

    page.navigate(s"http://localhost:$thisTestPort")
    assertThat(page.locator("h1")).containsText("HelloWorld");

    os.write.over(testDir / "hello.scala", helloWorldCode("Bye"))
    assertThat(page.locator("h1")).containsText("ByeWorld");

    os.write.append(styleDir / "styles.less", "h1 { color: red; }")
    assertThat(page.locator("h1")).hasCSS("color", "rgb(255, 0, 0)")

  }

  test("no proxy server") {
    val thisTestPort = 3000
    LiveServer
      .run(
        List(
          "--project-dir",
          testDir.toString,
          "--out-dir",
          outDir.toString,
          "--styles-dir",
          styleDir.toString,
          "--port",
          thisTestPort.toString
        )
      )
      .unsafeToFuture()

    Thread.sleep(4000) // give the thing time to start.

    val out = requests.get(s"http://localhost:$thisTestPort/api/hello", check = false)
    assertEquals(out.statusCode, 404)
  }

  test("proxy server") {
    val backendPort = 8089
    val thisTestPort = 3005
    // use http4s to instantiate a simple server that responds to /api/hello with 200, use Http4sEmberServer
    val backend = EmberServerBuilder
      .default[IO]
      .withHttpApp(
        HttpRoutes
          .of[IO] {
            case GET -> Root / "api" / "hello" =>
              Ok("hello world")
          }
          .orNotFound
      )
      .withPort(Port.fromInt(backendPort).get)
      .build
      .allocated
      .unsafeToFuture()

    LiveServer
      .run(
        List(
          "--project-dir",
          testDir.toString,
          "--out-dir",
          outDir.toString,
          "--styles-dir",
          styleDir.toString,
          "--port",
          thisTestPort.toString,
          "--proxy-target-port",
          backendPort.toString,
          "--proxy-prefix-path",
          "/api"
        )
      )
      .unsafeToFuture()

    Thread.sleep(500) // give the thing time to start.

    val out = requests.get(s"http://localhost:$thisTestPort/api/hello", check = false)
    assertEquals(out.statusCode, 200)
    assertEquals(out.text(), "hello world")

    val outFail = requests.get(s"http://localhost:$thisTestPort/api/nope", check = false)
    assertEquals(outFail.statusCode, 404)
  }

  test("no styles") {
    val thisTestPort = 3002
    LiveServer
      .run(
        List(
          "--project-dir",
          testDir.toString,
          "--out-dir",
          outDir.toString,
          "--port",
          thisTestPort.toString
        )
      )
      .unsafeToFuture()

    Thread.sleep(1000)

    val out = requests.get(s"http://localhost:$thisTestPort", check = false)
    assertEquals(out.statusCode, 200)
    assert(!out.text().contains("less"))

  }

  test("with styles") {
    val thisTestPort = 3003
    LiveServer
      .run(
        List(
          "--project-dir",
          testDir.toString,
          "--out-dir",
          outDir.toString,
          "--port",
          thisTestPort.toString,
          "--styles-dir",
          styleDir.toString
        )
      )
      .unsafeToFuture()

    Thread.sleep(1000)

    val out = requests.get(s"http://localhost:$thisTestPort", check = false)
    assertEquals(out.statusCode, 200)
    assert(out.text().contains("src=\"https://cdn.jsdelivr.net/npm/less"))
    assert(out.text().contains("less.watch()"))

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
