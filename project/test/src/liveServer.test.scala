import scala.compiletime.uninitialized

import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder

import com.comcast.ip4s.Port
import com.microsoft.playwright.*
import com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import LiveServer.LiveServerConfig
import LiveServer.openBrowserAtOpt

/*
Run
cs launch com.microsoft.playwright:playwright:1.41.1 -M "com.microsoft.playwright.CLI" -- install --with-deps
before this test, to make sure that the driver bundles are downloaded.
 */

class FirefoxSuite extends PlaywrightTest:

  override def beforeAll(): Unit =
    basePort = 5000
    pw = Playwright.create()
    browser = pw.firefox().launch(options);
    page = browser.newPage();
    page.setDefaultTimeout(30000)
  end beforeAll

end FirefoxSuite

class SafariSuite extends PlaywrightTest:

  override def beforeAll(): Unit =
    basePort = 4000
    pw = Playwright.create()
    browser = pw.webkit().launch(options);
    page = browser.newPage();
    page.setDefaultTimeout(30000)
  end beforeAll

end SafariSuite

class ChromeSuite extends PlaywrightTest:

  override def beforeAll(): Unit =
    basePort = 3000
    pw = Playwright.create()
    browser = pw.chromium().launch(options);
    page = browser.newPage();
    page.setDefaultTimeout(30000)
  end beforeAll

end ChromeSuite

trait PlaywrightTest extends CatsEffectSuite:

  var basePort: Int = uninitialized
  var pw: Playwright = uninitialized
  var browser: Browser = uninitialized
  var page: Page = uninitialized

  val options = new BrowserType.LaunchOptions()
  // options.setHeadless(false);

  // def testDir(base: os.Path) = os.pwd / "testDir"
  def outDir(base: os.Path) = base / ".out"
  def styleDir(base: os.Path) = base / "styles"

  val files =
    IO {
      // create a temp folder
      val tempDir = os.temp.dir()
      // create a file in the folder
      os.makeDir.all(styleDir(tempDir))
      os.write.over(tempDir / "hello.scala", helloWorldCode("Hello"))
      os.write.over(styleDir(tempDir) / "index.less", "")

      tempDir
    }.flatTap {
        tempDir =>
          IO.blocking(os.proc("scala-cli", "compile", tempDir.toString).call(cwd = tempDir))
      }
      .toResource

  // val externalHtmlStyles = FunFixture[(os.Path, os.Path)](
  //   setup = test =>
  //     // create a temp folder
  //     val tempDir = os.temp.dir()
  //     val staticDir = tempDir / "assets"
  //     os.makeDir(staticDir)
  //     // create a file in the folder
  //     os.write.over(tempDir / "hello.scala", helloWorldCode("Hello"))
  //     os.write.over(staticDir / "index.less", "h1{color:red}")
  //     os.write.over(staticDir / "index.html", vanillaTemplate(true).render)
  //     os.proc("scala-cli", "compile", tempDir.toString).call(cwd = tempDir)
  //     (tempDir, staticDir)
  //   ,
  //   teardown = tempDir =>
  //     // Always gets called, even if test failed.
  //     os.remove.all(tempDir._1)
  // )

  ResourceFunFixture {
    files.flatMap {
      dir =>
        val lsc = LiveServerConfig(
          baseDir = Some(dir.toString),
          stylesDir = Some(styleDir(dir).toString),
          port = Port.fromInt(basePort).get,
          openBrowserAt = "",
          preventBrowserOpen = true
        )
        LiveServer.main(lsc).map((_, dir, lsc.port))
    }

  }.test("incremental") {
    (_, testDir, port) =>
      val increaseTimeout = ContainsTextOptions()
      increaseTimeout.setTimeout(15000)

      IO(page.navigate(s"http://localhost:$port")) >>
        IO(assertThat(page.locator("h1")).containsText("HelloWorld", increaseTimeout)) >>
        IO.blocking(os.write.over(testDir / "hello.scala", helloWorldCode("Bye"))) >>
        IO(assertThat(page.locator("h1")).containsText("ByeWorld", increaseTimeout)) >>
        IO.blocking(os.write.append(styleDir(testDir) / "index.less", "h1 { color: red; }")) >>
        IO(assertThat(page.locator("h1")).hasCSS("color", "rgb(255, 0, 0)"))
  }

  // files.test("no proxy server") {
  //   testDir =>
  //     val thisTestPort = basePort + 2
  //     LiveServer
  //       .run(
  //         List(
  //           "--build-tool",
  //           "scala-cli",
  //           "--project-dir",
  //           testDir.toString,
  //           "--styles-dir",
  //           styleDir(testDir).toString,
  //           "--port",
  //           thisTestPort.toString
  //         )
  //       )
  //       .unsafeToFuture()

  //     Thread.sleep(1000) // give the thing time to start.

  //     val out = requests.get(s"http://localhost:$thisTestPort/api/hello", check = false)
  //     assertEquals(out.statusCode, 404)
  // }

  // files.test("proxy server") {
  //   testDir =>
  //     val backendPort = 8089
  //     val thisTestPort = basePort + 3
  //     // use http4s to instantiate a simple server that responds to /api/hello with 200, use Http4sEmberServer
  //     EmberServerBuilder
  //       .default[IO]
  //       .withHttpApp(
  //         HttpRoutes
  //           .of[IO] {
  //             case GET -> Root / "api" / "hello" =>
  //               Ok("hello world")
  //           }
  //           .orNotFound
  //       )
  //       .withPort(Port.fromInt(backendPort).get)
  //       .build
  //       .allocated
  //       .unsafeToFuture()

  //     LiveServer
  //       .run(
  //         List(
  //           "--build-tool",
  //           "scala-cli",
  //           "--project-dir",
  //           testDir.toString,
  //           "--styles-dir",
  //           styleDir(testDir).toString,
  //           "--port",
  //           thisTestPort.toString,
  //           "--proxy-target-port",
  //           backendPort.toString,
  //           "--proxy-prefix-path",
  //           "/api"
  //         )
  //       )
  //       .unsafeToFuture()

  //     Thread.sleep(1000) // give the thing time to start.

  //     val out = requests.get(s"http://localhost:$thisTestPort/api/hello", check = false)
  //     assertEquals(out.statusCode, 200)
  //     assertEquals(out.text(), "hello world")

  //     val outFail = requests.get(s"http://localhost:$thisTestPort/api/nope", check = false)
  //     assertEquals(outFail.statusCode, 404)
  // }

  // externalHtmlStyles.test("proxy server and SPA client apps") {
  //   testDir =>
  //     val backendPort = 8090
  //     val thisTestPort = basePort + 3
  //     // use http4s to instantiate a simple server that responds to /api/hello with 200, use Http4sEmberServer
  //     EmberServerBuilder
  //       .default[IO]
  //       .withHttpApp(
  //         HttpRoutes
  //           .of[IO] {
  //             case GET -> Root / "api" / "hello" =>
  //               Ok("hello world")
  //           }
  //           .orNotFound
  //       )
  //       .withPort(Port.fromInt(backendPort).get)
  //       .build
  //       .allocated
  //       .unsafeToFuture()

  //     LiveServer
  //       .run(
  //         List(
  //           "--build-tool",
  //           "scala-cli",
  //           "--project-dir",
  //           testDir._1.toString,
  //           "--path-to-index-html",
  //           testDir._2.toString,
  //           "--client-routes-prefix",
  //           "/app",
  //           "--port",
  //           thisTestPort.toString,
  //           "--proxy-target-port",
  //           backendPort.toString,
  //           "--proxy-prefix-path",
  //           "/api",
  //           "--log-level",
  //           "trace"
  //         )
  //       )
  //       .unsafeToFuture()

  //     Thread.sleep(1000) // give the thing time to start.

  //     val out = requests.get(s"http://localhost:$thisTestPort/api/hello", check = false)
  //     assertEquals(out.statusCode, 200)
  //     assertEquals(out.text(), "hello world")

  //     val outFail = requests.get(s"http://localhost:$thisTestPort/api/nope", check = false)
  //     assertEquals(outFail.statusCode, 404)

  //     val canGetHtml = requests.get(s"http://localhost:$thisTestPort", check = false)
  //     assertEquals(canGetHtml.statusCode, 200)

  // }

  // files.test("no styles") {
  //   testDir =>
  //     val thisTestPort = basePort + 4
  //     LiveServer
  //       .run(
  //         List(
  //           "--build-tool",
  //           "scala-cli",
  //           "--project-dir",
  //           testDir.toString,
  //           "--port",
  //           thisTestPort.toString
  //         )
  //       )
  //       .unsafeToFuture()

  //     Thread.sleep(1000)

  //     val out = requests.get(s"http://localhost:$thisTestPort", check = false)
  //     assertEquals(out.statusCode, 200)
  //     assert(!out.text().contains("less"))

  // }

  // files.test("with styles") {
  //   testDir =>
  //     val thisTestPort = basePort + 5
  //     LiveServer
  //       .run(
  //         List(
  //           "--build-tool",
  //           "scala-cli",
  //           "--project-dir",
  //           testDir.toString,
  //           "--port",
  //           thisTestPort.toString,
  //           "--styles-dir",
  //           styleDir(testDir).toString
  //           // "--log-level",
  //           // "debug"
  //         )
  //       )
  //       .unsafeToFuture()

  //     Thread.sleep(1000)

  //     val out = requests.get(s"http://localhost:$thisTestPort", check = false)
  //     assertEquals(out.statusCode, 200)
  //     assert(out.text().contains("src=\"https://cdn.jsdelivr.net/npm/less"))
  //     assert(out.text().contains("less.watch()"))

  //     val out2 = requests.get(s"http://localhost:$thisTestPort/index.less", check = false)
  //     assertEquals(out2.statusCode, 200)

  // }

  override def afterAll(): Unit =
    super.afterAll()
    pw.close()
  end afterAll

end PlaywrightTest

def helloWorldCode(greet: String) = s"""
//> using scala 3.3.3
//> using platform js

//> using dep org.scala-js::scalajs-dom::2.8.0
//> using dep com.raquo::laminar::17.0.0

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
      s"$greet",
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
