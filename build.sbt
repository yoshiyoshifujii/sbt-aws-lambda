name := "sbt-aws-lambda"

organization := "com.gilt.sbt"

scalaVersion := "2.10.4"

sbtPlugin := true

version in ThisBuild := "git describe --tags --always --dirty".!!.trim.replaceFirst("^v", "")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.13.0")

libraryDependencies ++= Seq(
  "com.amazonaws"  % "aws-java-sdk" % "1.10.11"
)
