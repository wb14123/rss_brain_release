import scala.language.postfixOps
import scala.sys.process._
import sbt._

name := "rss_brain"

version := "0.1"

scalaVersion := "2.13.16"

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


// I fixed a bug based in doobie but it's not released yet: https://github.com/typelevel/doobie/issues/2132
// TODO: Update to new version after it's released
lazy val doobieVersion = "1.0.0-RC5"

Test / parallelExecution := false
testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oF")

resolvers += Resolver.bintrayRepo("streamz", "maven") // for streamz
val streamzVersion = "0.13-RC4"
val akkaVersion = "2.8.0"
val esVersion = "8.9.2"
val prometheusVersion = "0.16.0"
val sttpVersion = "3.9.5"
val http4sVersion = "0.23.23"

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
  val file = (Compile / resourceManaged).value / "webview" / "static" / "dist"
  IO.copyDirectory(new File("./js/dist"), file, overwrite = true)
  IO.listFiles(file).toSeq
}.taskValue

assemblyMergeStrategy in assembly := {
  case x if x.endsWith("META-INF/services/io.grpc.LoadBalancerProvider") => MergeStrategy.first
  case x if x.endsWith("META-INF/services/io.grpc.NameResolverProvider") => MergeStrategy.first
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case PathList("module-info.class") => MergeStrategy.discard
  case x if x.endsWith("/module-info.class") => MergeStrategy.discard
  case x if x.matches("(.+)/protobuf/(\\w+).proto$") => MergeStrategy.discard
  case x if x.matches("(.+)/protobuf/(\\w+)/(\\w+).proto$") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

libraryDependencies ++= Seq(

  // http client
  "com.softwaremill.sttp.client3" %% "circe" % sttpVersion,
  "com.softwaremill.sttp.client3" %% "slf4j-backend" % sttpVersion,
  "com.softwaremill.sttp.client3" %% "http4s-backend" % sttpVersion,
  "io.circe" %% "circe-core" % "0.14.7",
  "io.circe" %% "circe-generic" % "0.14.7",
  "io.circe" %% "circe-parser" % "0.14.7",
  "io.circe" %% "circe-generic-extras" % "0.14.3",
  "io.circe" %% "circe-optics" % "0.15.0",

  "org.scala-lang.modules" %% "scala-xml" % "2.2.0", // xml parsing
  "com.typesafe" % "config" % "1.4.3", // config
  "org.jsoup" % "jsoup" % "1.17.2",
  "com.sendgrid" % "sendgrid-java" % "4.10.1",
  "com.stripe" % "stripe-java" % "26.0.0",
  "org.apache.commons" % "commons-text" % "1.11.0",

  // redis
  "io.lettuce" % "lettuce-core" % "6.2.3.RELEASE",

  // database
  "org.tpolecat" %% "doobie-core"     % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion,
  "org.tpolecat" %% "doobie-specs2"   % doobieVersion,
  "org.tpolecat" %% "doobie-hikari"   % doobieVersion,
  "io.getquill"  %% "quill-doobie"    % "4.8.4",
  "org.postgresql" % "postgresql"     % "42.7.4",

  // search
  "co.elastic.clients" % "elasticsearch-java" % esVersion,
  "com.sksamuel.elastic4s" %% "elastic4s-core" % esVersion,
  "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % esVersion,
  "com.sksamuel.elastic4s" %% "elastic4s-json-circe" % esVersion,
  "com.sksamuel.elastic4s" %% "elastic4s-effect-cats" % esVersion,

  // log
  "ch.qos.logback" % "logback-classic" % "1.2.10",

  // grpc
  "me.binwang.scala2grpc" %% "generator" % "1.0.1",

  // http server
  "org.http4s" %% "http4s-ember-client" % http4sVersion,
  "org.http4s" %% "http4s-ember-server" % http4sVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-prometheus-metrics" % "0.24.6",
  "org.http4s" %% "http4s-scalatags" % "0.25.2",

  // metrics
  "io.prometheus" % "simpleclient" % prometheusVersion,
  "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
  "io.prometheus" % "simpleclient_httpserver" % prometheusVersion,
  "me.binwang.archmage" %% "core" % "0.1.0",

  // throttling
  "com.google.guava" % "guava" % "31.1-jre",
  "com.bucket4j" % "bucket4j-core" % "8.10.1",

  // jwt, jws
  "com.nimbusds" % "nimbus-jose-jwt" % "9.39",
  "org.bouncycastle" % "bcprov-jdk18on" % "1.78.1",
  "org.bouncycastle" % "bcpkix-jdk18on" % "1.78.1",

  // testing
  "org.scalatest" % "scalatest_2.13" % "3.2.18" % Test,
  "org.mock-server" % "mockserver-netty" % "5.15.0" % Test,
  "org.scalacheck" %% "scalacheck" % "1.18.0" % Test,
  "org.scalamock" %% "scalamock" % "6.0.0" % Test,
)

dependencyOverrides ++= Seq(
  "org.slf4j" % "slf4j-api" % "1.7.30", // force use 1.7.X to be compatible
)

excludeDependencies ++= Seq(
  ExclusionRule("org.bouncycastle", "bcprov-jdk15on"),
  ExclusionRule("org.bouncycastle", "bcpkix-jdk15on"),
)
