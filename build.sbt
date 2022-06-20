ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.8"

enablePlugins(JavaAppPackaging)

lazy val root = (project in file("."))
  .settings(
    name := "minichain"
  )

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.19",
  "com.typesafe.akka" %% "akka-slf4j" % "2.6.19",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.19" % "test",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
  "org.scalatest" %% "scalatest" % "3.2.10" % "test"
)
