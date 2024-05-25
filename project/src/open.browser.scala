import cats.effect.IO
import scribe.Scribe
import java.net.URI
import java.awt.Desktop
import com.comcast.ip4s.Port

def openBrowser(openBrowserAt: Option[String], port: Port)(logger: Scribe[IO]): IO[Unit] =
  openBrowserAt match
    case None => logger.trace("No openBrowserAt flag set, so no browser will be opened")
    case Some(value) =>
      val openAt = URI(s"http://localhost:$port$value")
      logger.info(s"Attemptiong to open browser to $openAt") >>
        IO(
          if Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) then
            IO(Desktop.getDesktop().browse(openAt))
          else logger.error("Desktop not supported, so can't open browser")
        ).flatten
