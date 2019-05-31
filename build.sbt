inThisBuild(
  List(
    organization := "com.kubukoz",
    homepage := Some(url("https://github.com/kubukoz/slack-effect")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "kubukoz",
        "Jakub Kozłowski",
        "kubukoz@gmail.com",
        url("https://kubukoz.com")
      )
    )
  )
)

val compilerPlugins = List(
  compilerPlugin("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.full),
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.10.1")
)

val commonSettings = Seq(
  scalaVersion := "2.12.8",
  scalacOptions ++= Options.all,
  fork in Test := true,
  name := "slack-effect",
  updateOptions := updateOptions.value.withGigahorse(false), //may fix publishing bug
  libraryDependencies ++= Seq(
    "org.typelevel"     %% "cats-effect"          % "1.3.0",
    "com.spinoco"       %% "fs2-http"             % "0.4.0",
    "co.fs2"            %% "fs2-core"             % "1.0.4",
    "co.fs2"            %% "fs2-io"               % "1.0.4",
    "io.circe"          %% "circe-generic-extras" % "0.11.1",
    "io.circe"          %% "circe-generic"        % "0.11.1",
    "io.circe"          %% "circe-parser"         % "0.11.1",
    "io.chrisdavenport" %% "cats-par"             % "0.2.1",
    "io.chrisdavenport" %% "log4cats-slf4j"       % "0.3.0", //test
    "org.scalatest"     %% "scalatest"            % "3.0.7" % Test
  ) ++ compilerPlugins
)

val core = project.settings(commonSettings).settings(name += "-core")

val slackEffect =
  project.in(file(".")).settings(commonSettings).settings(skip in publish := true).dependsOn(core).aggregate(core)
