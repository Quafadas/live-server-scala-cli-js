import scala.compiletime.uninitialized
import scala.concurrent.duration.*

import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Uri
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

import com.comcast.ip4s.Port
import com.microsoft.playwright.*
import com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

import cats.effect.IO

import LiveServer.LiveServerConfig
import munit.CatsEffectSuite

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

  override val munitTimeout = Duration(90, "s") // windows super slow?

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
      val tempDir = os.temp.dir()
      os.makeDir.all(styleDir(tempDir))
      os.write.over(tempDir / "hello.scala", helloWorldCode("Hello"))
      os.write.over(styleDir(tempDir) / "index.less", "")
      tempDir
    }.flatTap {
        tempDir =>
          IO.blocking(os.proc(invokeScalaCliString, "compile", tempDir.toString).call(cwd = tempDir))
      }
      .toResource

  val externalHtmlStyles = IO {
    val tempDir = os.temp.dir()
    val staticDir = tempDir / "assets"
    os.makeDir(staticDir)
    os.write.over(tempDir / "hello.scala", helloWorldCode("Hello"))
    os.write.over(staticDir / "index.less", "h1{color:red}")
    os.write.over(staticDir / "index.html", vanillaTemplate(true).render)
    (tempDir, staticDir)
  }.flatTap {
      tempDir =>
        IO.blocking(os.proc(invokeScalaCliString, "compile", tempDir._1.toString).call(cwd = tempDir._1))
    }
    .toResource

  val client = EmberClientBuilder.default[IO].build

  val backendPort = 8999

  val simpleBackend = EmberServerBuilder
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

  ResourceFunFixture {
    files.flatMap {
      dir =>
        val lsc = LiveServerConfig(
          baseDir = Some(dir.toString),
          stylesDir = Some(styleDir(dir).toString),
          port = Port.fromInt(basePort).get,
          openBrowserAt = "",
          preventBrowserOpen = true,
          extraBuildArgs = List("--js-cli-on-jvm")
        )
        LiveServer.main(lsc).map((_, dir, lsc.port))
    }
  }.test("incremental") {
    (_, testDir, port) =>
      val increaseTimeout = ContainsTextOptions()
      increaseTimeout.setTimeout(15000)
      IO.sleep(3.seconds) >>
        IO(page.navigate(s"http://localhost:$port")) >>
        IO(assertThat(page.locator("h1")).containsText("HelloWorld", increaseTimeout)) >>
        IO.blocking(os.write.over(testDir / "hello.scala", helloWorldCode("Bye"))) >>
        IO(assertThat(page.locator("h1")).containsText("ByeWorld", increaseTimeout)) >>
        IO.blocking(os.write.append(styleDir(testDir) / "index.less", "h1 { color: red; }")) >>
        IO(assertThat(page.locator("h1")).hasCSS("color", "rgb(255, 0, 0)"))
  }

  ResourceFunFixture {
    files
      .both(client)
      .flatMap {
        (dir, client) =>
          val lsc = LiveServerConfig(
            baseDir = Some(dir.toString),
            stylesDir = Some(styleDir(dir).toString),
            port = Port.fromInt(basePort).get,
            openBrowserAt = "",
            preventBrowserOpen = true
          )
          LiveServer.main(lsc).map((_, dir, lsc.port, client))
      }
  }.test("no proxy server gives not found for a request to an API") {
    (_, _, port, client) =>
      assertIO(
        client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$port/api/hello"))),
        NotFound
      )
  }

  ResourceFunFixture {
    files
      .both(client)
      .flatMap {
        (dir, client) =>
          val backendPort = 8999

          val lsc = LiveServerConfig(
            baseDir = Some(dir.toString),
            stylesDir = Some(styleDir(dir).toString),
            port = Port.fromInt(basePort).get,
            openBrowserAt = "",
            preventBrowserOpen = true,
            proxyPortTarget = Port.fromInt(backendPort),
            proxyPathMatchPrefix = Some("/api")
          )

          simpleBackend.flatMap {
            _ =>
              LiveServer.main(lsc).map(_ => (lsc.port, client))
          }
      }
  }.test("proxy server forwards to a backend server") {
    (port, client) =>
      assertIO(
        client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$port/api/hello"))),
        Ok
      ) >>
        assertIO(
          client.expect[String](s"http://localhost:$port/api/hello"),
          "hello world"
        ) >>
        assertIO(
          client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$port/api/nope"))),
          NotFound
        )
  }

  ResourceFunFixture {
    externalHtmlStyles
      .both(client)
      .flatMap {
        case ((dir, extHtmlDir), client) =>
          println(dir)
          println(extHtmlDir)
          val lsc = LiveServerConfig(
            baseDir = Some(dir.toString),
            indexHtmlTemplate = Some(extHtmlDir.toString),
            port = Port.fromInt(basePort).get,
            openBrowserAt = "",
            preventBrowserOpen = true,
            proxyPortTarget = Port.fromInt(backendPort),
            proxyPathMatchPrefix = Some("/api"),
            clientRoutingPrefix = Some("/app"),
            logLevel = "info"
          )

          simpleBackend.flatMap {
            _ =>
              LiveServer.main(lsc).map(_ => (lsc.port, client))
          }
      }
  }.test("proxy server and SPA client apps") {
    (port, client) =>
      assertIO(
        client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$port/api/hello"))),
        Ok
      ) >>
        assertIO(
          client.expect[String](s"http://localhost:$port/api/hello"),
          "hello world"
        ) >>
        assertIO(
          client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$port/api/nope"))),
          NotFound
        ) >>
        assertIO(
          client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$port"))),
          Ok
        ) >>
        assertIO(
          client.expect[String](s"http://localhost:$port"),
          vanillaTemplate(true).render
        ) >>
        assertIO(
          client.expect[String](s"http://localhost:$port/app/spaRoute"),
          vanillaTemplate(true).render
        )

  }

  ResourceFunFixture {
    files.flatMap {
      dir =>
        val lsc = LiveServerConfig(
          baseDir = Some(dir.toString),
          port = Port.fromInt(basePort).get,
          openBrowserAt = "",
          preventBrowserOpen = true
        )
        LiveServer.main(lsc).flatMap(_ => client)
    }
  }.test("no styles") {
    client =>
      assertIO(
        client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$basePort"))),
        Ok
      ) >>
        assertIOBoolean(client.expect[String](s"http://localhost:$basePort").map(out => !out.contains("less")))
  }

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
        LiveServer.main(lsc).flatMap(_ => client)
    }
  }.test("with styles") {
    client =>
      assertIO(
        client.status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$basePort"))),
        Ok
      ) >>
        assertIOBoolean(
          client
            .expect[String](s"http://localhost:$basePort")
            .map(out => out.contains("src=\"https://cdn.jsdelivr.net/npm/less"))
        ) >>
        assertIOBoolean(
          client.expect[String](s"http://localhost:$basePort").map(out => out.contains("less.watch()"))
        ) >>
        assertIO(
          client
            .status(org.http4s.Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost:$basePort/index.less"))),
          Ok
        )
  }

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
