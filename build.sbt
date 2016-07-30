
lazy val baseSettings =
  sbtrelease.ReleasePlugin.releaseSettings ++
  GitVersion.settings ++
  scoverage.ScoverageSbtPlugin.projectSettings

lazy val buildSettings = baseSettings ++ Seq(
          organization := BuildSettings.organization,
          scalaVersion := Dependencies.Versions.scala,
         scalacOptions ++= BuildSettings.compilerFlags,
            crossPaths := true,
    crossScalaVersions := Dependencies.Versions.crossScala,
         sourcesInBase := false,
            exportJars := true,   // Needed for one-jar, with multi-project
     externalResolvers := BuildSettings.resolvers,
   checkLicenseHeaders := License.checkLicenseHeaders(streams.value.log, sourceDirectory.value),
  formatLicenseHeaders := License.formatLicenseHeaders(streams.value.log, sourceDirectory.value)
)

val commonDeps = Seq(
  Dependencies.jsr305,
  Dependencies.scalaLogging,
  Dependencies.slf4jApi,
  Dependencies.spectatorApi,
  Dependencies.typesafeConfig,
  Dependencies.scalatest % "test")

lazy val root = project.in(file("."))
  .configure(BuildSettings.profile)
  .aggregate(
    `atlas-akka`,
    `atlas-chart`,
    `atlas-config`,
    `atlas-core`,
    `atlas-jmh`,
    `atlas-json`,
    `atlas-module-akka`,
    `atlas-module-webapi`,
    `atlas-standalone`,
    `atlas-test`,
    `atlas-webapi`,
    `atlas-wiki`)
  .settings(buildSettings: _*)
  .settings(BuildSettings.noPackaging: _*)

lazy val `atlas-akka` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-json`)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)
  .settings(libraryDependencies ++= Seq(
    Dependencies.akkaActor,
    Dependencies.akkaSlf4j,
    Dependencies.iepService,
    Dependencies.spectatorSandbox,
    Dependencies.sprayCan,
    Dependencies.sprayRouting,
    Dependencies.typesafeConfig,
    Dependencies.akkaTestkit % "test",
    Dependencies.sprayTestkit % "test"
  ))

lazy val `atlas-chart` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-core`, `atlas-json`, `atlas-test` % "test")
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)

lazy val `atlas-config` = project
  .configure(BuildSettings.profile)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)

lazy val `atlas-core` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-config`)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)
  .settings(libraryDependencies ++= Seq(
    Dependencies.caffeine,
    Dependencies.equalsVerifier % "test",
    Dependencies.jol % "test"
  ))

lazy val `atlas-jmh` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-core`)
  .enablePlugins(pl.project13.scala.sbt.SbtJmh)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)

lazy val `atlas-json` = project
  .configure(BuildSettings.profile)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)
  .settings(libraryDependencies ++= Seq(
    Dependencies.jacksonCore2,
    Dependencies.jacksonJoda2,
    Dependencies.jacksonMapper2,
    Dependencies.jacksonScala2,
    Dependencies.jacksonSmile2,
    Dependencies.jodaConvert
  ))

lazy val `atlas-module-akka` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-akka`)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)
  .settings(libraryDependencies ++= Seq(
    Dependencies.guiceCore,
    Dependencies.guiceMulti,
    Dependencies.iepGuice
  ))

lazy val `atlas-module-webapi` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-webapi`)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)
  .settings(libraryDependencies ++= Seq(
    Dependencies.guiceCore,
    Dependencies.iepGuice
  ))

lazy val `atlas-standalone` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-module-akka`, `atlas-module-webapi`)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)
  .settings(libraryDependencies ++= Seq(
    Dependencies.iepGuice,
    Dependencies.guiceCore,
    Dependencies.guiceMulti,
    Dependencies.log4jApi,
    Dependencies.log4jCore,
    Dependencies.log4jSlf4j,
    Dependencies.spectatorLog4j
  ))

lazy val `atlas-test` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-core`)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= Seq(
    Dependencies.scalatest
  ))

lazy val `atlas-webapi` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-akka`, `atlas-chart`, `atlas-core`, `atlas-json`, `atlas-test` % "test")
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= commonDeps)
  .settings(libraryDependencies ++= Seq(
    Dependencies.spectatorSandbox,
    Dependencies.akkaTestkit % "test",
    Dependencies.sprayTestkit % "test"
  ))

lazy val `atlas-wiki` = project
  .configure(BuildSettings.profile)
  .dependsOn(`atlas-core`, `atlas-webapi`)
  .settings(buildSettings: _*)
  .settings(libraryDependencies ++= Seq(
    Dependencies.scalaCompiler
  ))

lazy val checkLicenseHeaders = taskKey[Unit]("Check the license headers for all source files.")
lazy val formatLicenseHeaders = taskKey[Unit]("Fix the license headers for all source files.")
