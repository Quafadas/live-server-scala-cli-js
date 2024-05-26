// import org.http4s.HttpRoutes
// import org.typelevel.ci.CIStringSyntax

// import fs2.concurrent.Topic
// import fs2.io.file.Path

// import cats.effect.IO
// import cats.effect.kernel.Ref
// import cats.effect.std.MapRef

// import munit.CatsEffectSuite

// import scala.concurrent.duration.*

// import scala.compiletime.uninitialized

// import org.http4s.HttpRoutes
// import org.http4s.dsl.io.*
// import org.http4s.ember.server.EmberServerBuilder

// import com.comcast.ip4s.Port
// import com.microsoft.playwright.*
// import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

// import cats.effect.IO
// import cats.effect.unsafe.implicits.global
// import com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions

// class IntegrationSuite extends CatsEffectSuite:

//   var pw: Playwright = uninitialized
//   var browser: Browser = uninitialized
//   var page: Page = uninitialized

//   val options = new BrowserType.LaunchOptions()
//   // options.setHeadless(false);

//   // def testDir(base: os.Path) = os.pwd / "testDir"
//   def outDir(base: os.Path) = base / ".out"
//   def styleDir(base: os.Path) = base / "styles"

//   override def beforeAll(): Unit =
//     pw = Playwright.create()
//     browser = pw.chromium().launch(options);
//     page = browser.newPage();
//     page.setDefaultTimeout(30000)
//   end beforeAll

//   val files = FunFixture[os.Path](
//     setup = test =>
//       // create a temp folder
//       val tempDir = os.temp.dir()
//       // create a file in the folder
//       os.makeDir.all(styleDir(tempDir))
//       os.write.over(tempDir / "hello.scala", helloWorldCode("Hello"))
//       os.write.over(styleDir(tempDir) / "index.less", "")
//       os.proc("scala-cli", "compile", tempDir.toString).call(cwd = tempDir)
//       tempDir
//     ,
//     teardown = tempDir =>
//       // Always gets called, even if test failed.
//       os.remove.all(tempDir)
//   )

//   files.test("incremental".only) {
//     testDir =>
//       val increaseTimeout = ContainsTextOptions()
//       increaseTimeout.setTimeout(15000)

//       LiveServer
//         .run(
//           List(
//             "--build-tool",
//             "scala-cli",
//             "--project-dir",
//             testDir.toString,
//             "--styles-dir",
//             styleDir(testDir).toString,
//             "--port",
//             5000.toString,
//             "--log-level",
//             "debug"
//           )
//         )
//         .toResource
//         .use {
//           _ =>
//             IO.blocking {
//               page.navigate(s"http://localhost:3000")
//               assertThat(page.locator("h1")).containsText("HelloWorld", increaseTimeout)
//             }
//         }

//   }

//   override def afterAll(): Unit =
//     super.afterAll()
//     pw.close()
//   end afterAll

// end IntegrationSuite
