import scala.language.postfixOps
import scala.sys.process._
import sbt._

name := "rss_brain"

version := "0.1"

scalaVersion := "2.13.18"

enablePlugins(Fs2Grpc)
enablePlugins(Scala2GrpcPlugin)

coverageEnabled := false
coverageExcludedPackages := "me.binwang.rss.grpc.*"

grpcGeneratorMainClass := "me.binwang.rss.grpc.generator.GenerateGRPC"

Compile / scalacOptions += "-Ymacro-annotations"

dependencyCheckAssemblyAnalyzerEnabled := Option(false)
dependencyCheckFailBuildOnCVSS := 4

fork := true

javacOptions ++= Seq("-source", "21", "-target", "21", "-Xlint")

javaOptions += "-Xmx2G"

initialize := {
  val _ = initialize.value
  val javaVersion = sys.props("java.specification.version")
  if (javaVersion != "21")
    sys.error("Java 21 is required for this project. Found " + javaVersion + " instead")
}


val doobieVersion = "1.0.0-RC12"

Test / parallelExecution := false
Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF")

val elastic4sVersion = "8.19.3"
val prometheusVersion = "1.10.0"
val sttpVersion = "4.1.2"
val http4sVersion = "0.23.38"

// do not run generate and validate in the same sbt run since validate will load old resource

lazy val generateRsshubRules = taskKey[Unit]("Generate RSSHub rules")
generateRsshubRules := Def.taskDyn {
  val resourceDir = (Compile / resourceDirectory).value
  Def.task {
    (Compile / runMain).toTask(s" me.binwang.rss.sourcefinder.RsshubRulesConverter $resourceDir/rsshub-regex-rules.json generate").value
  }
}.value

lazy val validateRsshubRules = taskKey[Unit]("Validate RSSHub rules")
validateRsshubRules := Def.taskDyn {
  val resourceDir = (Compile / resourceDirectory).value
  Def.task {
    (Compile / runMain).toTask(s" me.binwang.rss.sourcefinder.RsshubRulesConverter $resourceDir/rsshub-regex-rules.json validate").value
  }
}.value

lazy val webpack = taskKey[Unit]("Run webpack in js directory")
webpack :=  {
  val workDir = new File("./js")
  Process("npm" :: "install" :: Nil, workDir) #&& Process("npx" :: "webpack" :: Nil, workDir) !
}

Compile / resourceGenerators += Def.task {
  webpack.value
  val distDir = (Compile / resourceManaged).value / "webview" / "static" / "dist"
  // Clean stale files from previous builds (old hashed files) before copying new ones
  IO.delete(distDir)
  IO.copyDirectory(new File("./js/dist"), distDir, overwrite = true)
  IO.listFiles(distDir).toSeq
}.taskValue

assembly / assemblyMergeStrategy := {
  case x if x.endsWith("META-INF/services/io.grpc.LoadBalancerProvider") => MergeStrategy.first
  case x if x.endsWith("META-INF/services/io.grpc.NameResolverProvider") => MergeStrategy.first
  case x if x.endsWith("META-INF/services/org.slf4j.spi.SLF4JServiceProvider") => MergeStrategy.first
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case PathList("module-info.class") => MergeStrategy.discard
  case x if x.endsWith("/module-info.class") => MergeStrategy.discard
  case x if x.matches("(.+)/protobuf/(\\w+).proto$") => MergeStrategy.discard
  case x if x.matches("(.+)/protobuf/(\\w+)/(\\w+).proto$") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

libraryDependencies ++= Seq(

  // http client
  "com.softwaremill.sttp.client4" %% "circe" % sttpVersion,
  "com.softwaremill.sttp.client4" %% "slf4j-backend" % sttpVersion,
  "com.softwaremill.sttp.client4" %% "http4s-backend" % sttpVersion,
  "io.circe" %% "circe-core" % "0.14.17",
  "io.circe" %% "circe-generic" % "0.14.17",
  "io.circe" %% "circe-parser" % "0.14.17",
  "io.circe" %% "circe-generic-extras" % "0.14.4",
  "io.circe" %% "circe-optics" % "0.15.1",

  "org.scala-lang.modules" %% "scala-xml" % "2.5.0", // xml parsing
  "com.typesafe" % "config" % "1.4.10", // config
  "org.jsoup" % "jsoup" % "1.24.1",
  "com.sendgrid" % "sendgrid-java" % "4.10.3",
  "com.stripe" % "stripe-java" % "34.1.0",
  "org.apache.commons" % "commons-text" % "1.15.0",

  // redis
  "io.lettuce" % "lettuce-core" % "7.9.0.RELEASE",

  // database
  "org.tpolecat" %% "doobie-core"     % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion,
  "org.tpolecat" %% "doobie-specs2"   % doobieVersion,
  "org.tpolecat" %% "doobie-hikari"   % doobieVersion,
  "io.getquill"  %% "quill-jdbc"      % "4.8.5", // dialects used by dao/sql/DoobieContext.scala
  "org.postgresql" % "postgresql"     % "42.7.14",

  // search
  "nl.gn0s1s" %% "elastic4s-core" % elastic4sVersion,
  "nl.gn0s1s" %% "elastic4s-client-esjava" % elastic4sVersion,
  "nl.gn0s1s" %% "elastic4s-json-circe" % elastic4sVersion,
  "nl.gn0s1s" %% "elastic4s-effect-cats" % elastic4sVersion,

  // log
  "ch.qos.logback" % "logback-classic" % "1.6.6",

  // grpc
  "me.binwang.scala2grpc" %% "generator" % "1.3.0",

  // http server
  "org.http4s" %% "http4s-ember-client" % http4sVersion,
  "org.http4s" %% "http4s-ember-server" % http4sVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-scalatags" % "0.25.4",

  // metrics
  "io.prometheus" % "prometheus-metrics-core" % prometheusVersion,
  "io.prometheus" % "prometheus-metrics-instrumentation-jvm" % prometheusVersion,
  "io.prometheus" % "prometheus-metrics-exposition-textformats" % prometheusVersion,
  "me.binwang.archmage" %% "core" % "0.1.0",

  // throttling
  "com.google.guava" % "guava" % "33.8.0-jre",
  "com.bucket4j" % "bucket4j_jdk17-core" % "8.22.0",

  // jwt, jws
  "com.nimbusds" % "nimbus-jose-jwt" % "10.10",
  "org.bouncycastle" % "bcprov-jdk18on" % "1.87",
  "org.bouncycastle" % "bcpkix-jdk18on" % "1.87",

  // testing
  "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  "org.mock-server" % "mockserver-netty" % "9.2.0" % Test,
  "org.scalacheck" %% "scalacheck" % "1.20.0" % Test,
  "org.scalamock" %% "scalamock" % "7.6.3" % Test,
  "org.scalamock" %% "scalamock-scalatest" % "7.6.3" % Test,
)

dependencyOverrides ++= Seq(
  "org.slf4j" % "slf4j-api" % "2.0.20", // keep transitive dependencies on the same slf4j line as logback
)

// scala2grpc 1.3.0 pins an older fs2-grpc-runtime than sbt-fs2-grpc provides
libraryDependencySchemes += "org.typelevel" %% "fs2-grpc-runtime" % VersionScheme.Always
