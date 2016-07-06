name := "sbt-aws-lambda"

organization := "com.gilt.sbt"

sbtPlugin := true

version in ThisBuild := "git describe --tags --always --dirty".!!.trim.replaceFirst("^v", "")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.13.0")

val awsSdkVersion = "1.10.27"

libraryDependencies ++= Seq(
  "com.amazonaws"  % "aws-java-sdk-iam"    % awsSdkVersion,
  "com.amazonaws"  % "aws-java-sdk-lambda" % awsSdkVersion,
  "com.amazonaws"  % "aws-java-sdk-s3"     % awsSdkVersion
)

javaVersionPrefix in javaVersionCheck := Some("1.8")
