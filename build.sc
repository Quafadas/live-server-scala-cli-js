import os.copy.over
import $ivy.`io.github.quafadas::millSite::0.0.24`
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import $ivy.`com.goyeau::mill-scalafix::0.4.0`
import $file.playwrightVersion // used to cache in GHA

import io.github.quafadas.millSite._
import mill._, scalalib._, publish._, scalanativelib._
import mill.scalalib.scalafmt.ScalafmtModule
import de.tobiasroeser.mill.vcs.version._

import com.goyeau.mill.scalafix.ScalafixModule
import java.text.Format

object V{

  val http4sVersion = "0.23.27"
  val circeVersion = "0.14.9"
}

trait FormatFix extends ScalafmtModule with ScalafixModule with ScalaModule

trait FormatFixPublish extends ScalaModule with FormatFix with PublishModule{
  override def scalaVersion = "3.4.2"

  override def scalacOptions: Target[Seq[String]] = super.scalacOptions() ++ Seq("-Wunused:all")

  def publishVersion = VcsVersion.vcsState().format()

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

trait Testy extends TestModule.Munit with FormatFix {

  override def defaultCommandName(): String = "test"

  def ivyDeps = super.ivyDeps() ++ sjsls.ivyDeps() ++ Seq(
    ivy"org.typelevel::munit-cats-effect::2.0.0",
    ivy"org.scalameta::munit::1.0.0",
    ivy"com.lihaoyi::os-lib:0.10.2"
  )

}

object routes extends FormatFixPublish {

  def scalaVersion: T[String] = "3.3.3" // Latest LTS

  def ivyDeps = Agg(
    ivy"org.http4s::http4s-core:${V.http4sVersion}",
    ivy"org.http4s::http4s-client:${V.http4sVersion}",
    ivy"org.http4s::http4s-server:${V.http4sVersion}",
    ivy"org.http4s::http4s-dsl::${V.http4sVersion}",
    ivy"com.outr::scribe-cats::3.15.0"
  )

  override def artifactName = "frontend-routes"

  object test extends Testy with ScalaTests{
    def ivyDeps = super.ivyDeps() ++ sjsls.ivyDeps()
  }

}

object sjsls extends FormatFixPublish {

  override def scalaVersion = "3.4.2" // Latest

  def ivyDeps = super.ivyDeps() ++ Seq(
    ivy"org.http4s::http4s-ember-server::${V.http4sVersion}",
    ivy"org.http4s::http4s-ember-client::${V.http4sVersion}",
    ivy"org.http4s::http4s-scalatags::0.25.2",
    ivy"io.circe::circe-core::${V.circeVersion}",
    ivy"io.circe::circe-generic::${V.circeVersion}",
    ivy"co.fs2::fs2-io::3.10.2",
    ivy"com.lihaoyi::scalatags::0.13.1",
    ivy"com.monovore::decline::2.4.1",
    ivy"com.monovore::decline-effect::2.4.1",

  )

  def moduleDeps = Seq(routes)

  def artifactName = "sjsls"

  object test extends Testy with ScalaTests {
    def ivyDeps = super.ivyDeps() ++ sjsls.ivyDeps() ++ Seq(

      ivy"com.microsoft.playwright:playwright:${playwrightVersion.pwV}",
      ivy"com.microsoft.playwright:driver-bundle:${playwrightVersion.pwV}"
    )
  }
  //def scalaNativeVersion = "0.4.17" // aspirational :-)

}

object site extends SiteModule {

   def scalaVersion = sjsls.scalaVersion

  override def moduleDeps = Seq(sjsls)

}

// SN deps which aren't yet there.
/**
1 targets failed
project.resolvedIvyDeps
Resolution failed for 2 modules:
--------------------------------------------
  com.outr:scribe-cats_native0.4_3:3.13.5
        not found: /Users/simon/.ivy2/local/com.outr/scribe-cats_native0.4_3/3.13.5/ivys/ivy.xml
        not found: https://repo1.maven.org/maven2/com/outr/scribe-cats_native0.4_3/3.13.5/scribe-cats_native0.4_3-3.13.5.pom
--------------------------------------------

For additional information on library dependencies, see the docs at
https://mill-build.com/mill/Library_Dependencies.html

**/