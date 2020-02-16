import sbt._

object Library {

  val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1"
  val scalaTestDep: Seq[ModuleID] = Seq(scalaTest % Test)

  val antlrDep: ModuleID = "org.antlr" % "antlr4-runtime" % "4.7" % "provided"

  val json4s = "org.json4s" %% "json4s-native" % "3.2.11"

  // If you are developing Cobalt SDK locally.
  val repositories: Seq[MavenRepository] = Seq(
    "Typesafe" at "http://repo.typesafe.com/typesafe/releases/",
    "Java.net Maven2 Repository" at "http://download.java.net/maven/2/",
    "spray repo" at "http://repo.spray.io",
  )
}
