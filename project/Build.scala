import sbt._
import Keys._

object BuildSettings {
  val paradiseVersion = "2.0.0"
  val buildSettings = Defaults.defaultSettings ++ Seq(
    organization := "us.insolit",
    version := "0.1-SNAPSHOT",
    scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation"), //, "-Ymacro-debug-lite"),
    scalaVersion := "2.11.1",
    crossScalaVersions := Seq("2.11.0", "2.11.1"),
    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += Resolver.sonatypeRepo("releases"),
    addCompilerPlugin("org.scalamacros" % "paradise" % paradiseVersion cross CrossVersion.full),
    libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _),
    libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-compiler" % _),
    libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.0"
  )
}

object FreezeBuild extends Build {
  import BuildSettings._

  lazy val root: Project = Project(
    id = "root",
    base = file("."),
    settings = buildSettings ++ Seq(
      run <<= run in Compile in test
    )
  ) aggregate(macros, test)

  lazy val macros: Project = Project(
    id = "macros",
    base = file("macros"),
    settings = buildSettings ++ Seq(
      libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _),
      libraryDependencies ++= (
        if (scalaVersion.value.startsWith("2.10")) List("org.scalamacros" %% "quasiquotes" % paradiseVersion)
        else Nil
      )
    )
  )

  lazy val test: Project = Project(
    id = "test",
    base = file("test"),
    settings = buildSettings
  ) dependsOn(macros)
}
