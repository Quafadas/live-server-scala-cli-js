import $ivy.`io.github.quafadas::millSite::0.0.19`
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import $file.playwrightVersion // used to cache in GHA

import io.github.quafadas.millSite._
import mill._, scalalib._, publish._
import mill.scalalib.scalafmt.ScalafmtModule
import de.tobiasroeser.mill.vcs.version._


object project extends ScalaModule with PublishModule with ScalafmtModule {
  def scalaVersion = "3.4.1"
  def ivyDeps = super.ivyDeps() ++ Seq(
    ivy"org.http4s::http4s-ember-server::0.23.26",
    ivy"org.http4s::http4s-dsl::0.23.26",
    ivy"org.http4s::http4s-scalatags::0.25.2",
    ivy"io.circe::circe-core::0.14.6",
    ivy"io.circe::circe-generic::0.14.6",
    ivy"co.fs2::fs2-io::3.10.2",
    ivy"com.lihaoyi::scalatags::0.12.0"
  )

  def artifactName = "live-server-scala-cli-js"

  def publishVersion = VcsVersion.vcsState().format()

  object test extends ScalaTests with TestModule.Munit {
    def ivyDeps = super.ivyDeps() ++ project.ivyDeps() ++ Seq(
      ivy"org.scalameta::munit::1.0.0-M11",
      ivy"com.microsoft.playwright:playwright:${playwrightVersion.pwV}",
      ivy"com.microsoft.playwright:driver-bundle:${playwrightVersion.pwV}",
      ivy"com.lihaoyi::os-lib:0.9.3"
    )
  }

  override def pomSettings = T {
    PomSettings(
      description = "An experimental live server for scala JS projects",
      organization = "io.github.quafadas",
      url = "https://github.com/Quafadas/live-server-scala-cli-js",
      licenses = Seq(License.`Apache-2.0`),
      versionControl =
        VersionControl.github("quafadas", "live-server-scala-cli-js"),
      developers = Seq(
        Developer("quafadas", "Simon Parten", "https://github.com/quafadas")
      )
    )
  }


}
