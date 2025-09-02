package io.github.quafadas.sjsls

import cats.effect._
import com.comcast.ip4s._
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

class DezombieTest extends CatsEffectSuite:

  test("That we kill off a zombie server") {
    val portInt = 6789
    val port = Port.fromInt(portInt).get

    val lsc = LiveServerConfig(
                baseDir = None,
                stylesDir = None,
                port = port,
                buildTool = NoBuildTool(),
                openBrowserAt = "",
                preventBrowserOpen = true,
                dezombify = true,
                logLevel = "debug",
              )

    for {
      // Start first server in a separate process using mill run
      _ <- IO.println("You should have already started a zombie server in separate process...")

      // Check if port is actually in use
      portInUse <- checkPortInUse(port)
      _ <- IO(assert(portInUse)) // TODO, this needs to be co-ordinated with some external process. See forkEnv

      // Now start second server with dezombify enabled - this should kill the first one
      _ <- IO.println("Starting second server with `enabled...")
      allocated <- LiveServer.main(lsc).allocated
      (server2, release2) = allocated
      _ <- IO.println("Second server started successfully!")

    } yield
      (())
  }