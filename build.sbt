import Util._
import Dependencies._
import Scripted._
import Sxr.sxr

// ThisBuild settings take lower precedence,
// but can be shared across the multi projects.
def buildLevelSettings: Seq[Setting[_]] = inThisBuild(Seq(
  organization := "org.scala-sbt",
  version := "0.13.13-SNAPSHOT",
  bintrayOrganization := Some(if (publishStatus.value == "releases") "typesafe" else "sbt"),
  bintrayRepository := s"ivy-${publishStatus.value}",
  bintrayPackage := "sbt",
  bintrayReleaseOnPublish := false
))

def commonSettings: Seq[Setting[_]] = Seq(
  scalaVersion := scala210,
  publishArtifact in packageDoc := false,
  publishMavenStyle := false,
  componentID := None,
  crossPaths := false,
  resolvers += Resolver.typesafeIvyRepo("releases"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  concurrentRestrictions in Global += Util.testExclusiveRestriction,
  testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-w", "1"),
  javacOptions in compile ++= Seq("-target", "6", "-source", "6", "-Xlint", "-Xlint:-serial"),
  incOptions := incOptions.value.withNameHashing(true),
  crossScalaVersions := Seq(scala210),
  bintrayPackage := (bintrayPackage in ThisBuild).value,
  bintrayRepository := (bintrayRepository in ThisBuild).value,
  test in assembly := {},
  assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = true),
  assemblyMergeStrategy in assembly := {
    case PathList(ps @ _*) if ps.last == "javax.inject.Named"      => MergeStrategy.first
    case PathList(ps @ _*) if ps.last endsWith ".class"            => MergeStrategy.first
    case PathList(ps @ _*) if ps.last endsWith "module.properties" => MergeStrategy.first
    case PathList(ps @ _*) if ps.last == "MANIFEST.MF"             => MergeStrategy.rename
    case "LICENSE"                                                 => MergeStrategy.first
    case "NOTICE"                                                  => MergeStrategy.first
    // excluded from fat jar because otherwise we may pick it up when determining the `actualVersion`
    // of other scala instances.
    case "compiler.properties"                                     => MergeStrategy.discard

    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  }
)

def minimalSettings: Seq[Setting[_]] =
  commonSettings ++ customCommands ++
  publishPomSettings ++ Release.javaVersionCheckSettings

def baseSettings: Seq[Setting[_]] =
  minimalSettings ++ Seq(projectComponent) ++ baseScalacOptions ++ Licensed.settings ++ Formatting.settings

def testedBaseSettings: Seq[Setting[_]] =
  baseSettings ++ testDependencies


val altLocalRepoName = "alternative-local"
val altLocalRepoPath = sys.props("user.home") + "/.ivy2/sbt-alternative"
lazy val altLocalResolver = Resolver.file(altLocalRepoName, file(sys.props("user.home") + "/.ivy2/sbt-alternative"))(Resolver.ivyStylePatterns)
lazy val altLocalPublish = TaskKey[Unit]("alt-local-publish", "Publishes an artifact locally to an alternative location.")
def altPublishSettings: Seq[Setting[_]] = Seq(
  resolvers += altLocalResolver,
  altLocalPublish := {
    val config = (Keys.publishLocalConfiguration).value
    val moduleSettings = (Keys.moduleSettings).value
    val ivy = new IvySbt((ivyConfiguration.value))

    val module =
        new ivy.Module(moduleSettings)
    val newConfig =
       new PublishConfiguration(
           config.ivyFile,
           altLocalRepoName,
           config.artifacts,
           config.checksums,
           config.logging)
    streams.value.log.info("Publishing " + module + " to local repo: " + altLocalRepoName)
    IvyActions.publish(module, newConfig, streams.value.log)
  })

lazy val sbtRoot: Project = (project in file(".")).
  configs(Sxr.sxrConf).
  aggregate(nonRoots: _*).
  settings(
    buildLevelSettings,
    minimalSettings,
    rootSettings,
    publish := {},
    publishLocal := {}
  )

// This is used to configure an sbt-launcher for this version of sbt.
lazy val bundledLauncherProj =
  (project in file("launch")).
  settings(
    minimalSettings,
    inConfig(Compile)(Transform.configSettings),
    Release.launcherSettings(sbtLaunchJar)
  ).
  enablePlugins(SbtLauncherPlugin).
  settings(
    name := "sbt-launch",
    moduleName := "sbt-launch",
    description := "sbt application launcher",
    publishArtifact in packageSrc := false,
    autoScalaLibrary := false,
    publish := Release.deployLauncher.value,
    publishLauncher := Release.deployLauncher.value,
    packageBin in Compile := sbtLaunchJar.value
  )

/* ** subproject declarations ** */

// defines Java structures used across Scala versions, such as the API structures and relationships extracted by
//   the analysis compiler phases and passed back to sbt.  The API structures are defined in a simple
//   format from which Java sources are generated by the datatype generator Projproject
lazy val interfaceProj = (project in file("interface")).
  settings(
    minimalSettings,
    javaOnlySettings,
    name := "Interface",
    projectComponent,
    exportJars := true,
    componentID := Some("xsbti"),
    watchSources <++= apiDefinitions,
    resourceGenerators in Compile <+= (version, resourceManaged, streams, compile in Compile) map generateVersionFile,
    apiDefinitions <<= baseDirectory map { base => (base / "definition") :: (base / "other") :: (base / "type") :: Nil },
    sourceGenerators in Compile <+= (apiDefinitions,
      fullClasspath in Compile in datatypeProj,
      sourceManaged in Compile,
      mainClass in datatypeProj in Compile,
      runner,
      streams) map generateAPICached,
    altPublishSettings
  )

// defines operations on the API of a source, including determining whether it has changed and converting it to a string
//   and discovery of Projclasses and annotations
lazy val apiProj = (project in compilePath / "api").
  dependsOn(interfaceProj, classfileProj).
  settings(
    testedBaseSettings,
    name := "API"
  )

/* **** Utilities **** */

lazy val controlProj = (project in utilPath / "control").
  settings(
    baseSettings,
    Util.crossBuild,
    name := "Control",
    crossScalaVersions := Seq(scala210, scala211)
  )

lazy val collectionProj = (project in utilPath / "collection").
  settings(
    testedBaseSettings,
    Util.keywordsSettings,
    Util.crossBuild,
    name := "Collections",
    crossScalaVersions := Seq(scala210, scala211)
  )

lazy val applyMacroProj = (project in utilPath / "appmacro").
  dependsOn(collectionProj).
  settings(
    testedBaseSettings,
    name := "Apply Macro",
    libraryDependencies += scalaCompiler.value
  )

// The API for forking, combining, and doing I/O with system processes
lazy val processProj = (project in utilPath / "process").
  dependsOn(ioProj % "test->test").
  settings(
    baseSettings,
    name := "Process",
    libraryDependencies ++= scalaXml.value
  )

// Path, IO (formerly FileUtilities), NameFilter and other I/O utility classes
lazy val ioProj = (project in utilPath / "io").
  dependsOn(controlProj).
  settings(
    testedBaseSettings,
    Util.crossBuild,
    name := "IO",
    libraryDependencies += scalaCompiler.value % Test,
    crossScalaVersions := Seq(scala210, scala211)
  )

// Utilities related to reflection, managing Scala versions, and custom class loaders
lazy val classpathProj = (project in utilPath / "classpath").
  dependsOn(interfaceProj, ioProj).
  settings(
    testedBaseSettings,
    name := "Classpath",
    libraryDependencies ++= Seq(scalaCompiler.value,Dependencies.launcherInterface)
  )

// Command line-related utilities.
lazy val completeProj = (project in utilPath / "complete").
  dependsOn(collectionProj, controlProj, ioProj).
  settings(
    testedBaseSettings,
    Util.crossBuild,
    name := "Completion",
    libraryDependencies += jline,
    crossScalaVersions := Seq(scala210, scala211)
  )

// logging
lazy val logProj = (project in utilPath / "log").
  dependsOn(interfaceProj, processProj).
  settings(
    testedBaseSettings,
    name := "Logging",
    libraryDependencies += jline
  )

// Relation
lazy val relationProj = (project in utilPath / "relation").
  dependsOn(interfaceProj, processProj).
  settings(
    testedBaseSettings,
    name := "Relation"
  )

// class file reader and analyzer
lazy val classfileProj = (project in utilPath / "classfile").
  dependsOn(ioProj, interfaceProj, logProj).
  settings(
    testedBaseSettings,
    name := "Classfile"
  )

// generates immutable or mutable Java data types according to a simple input format
lazy val datatypeProj = (project in utilPath / "datatype").
  dependsOn(ioProj).
  settings(
    baseSettings,
    name := "Datatype Generator"
  )

// cross versioning
lazy val crossProj = (project in utilPath / "cross").
  settings(
    baseSettings,
    inConfig(Compile)(Transform.crossGenSettings),
    name := "Cross"
  )

// A logic with restricted negation as failure for a unique, stable model
lazy val logicProj = (project in utilPath / "logic").
  dependsOn(collectionProj, relationProj).
  settings(
    testedBaseSettings,
    name := "Logic"
  )

/* **** Intermediate-level Modules **** */

// Apache Ivy integration
lazy val ivyProj = (project in file("ivy")).
  dependsOn(interfaceProj, crossProj, logProj % "compile;test->test", ioProj % "compile;test->test", /*launchProj % "test->test",*/ collectionProj).
  settings(
    baseSettings,
    name := "Ivy",
    libraryDependencies ++= Seq(ivy, jsch, sbtSerialization, scalaReflect.value, launcherInterface),
    testExclusive)

// Runner for uniform test interface
lazy val testingProj = (project in file("testing")).
  dependsOn(ioProj, classpathProj, logProj, testAgentProj).
  settings(
    baseSettings,
    name := "Testing",
    libraryDependencies ++= Seq(testInterface,launcherInterface)
  )

// Testing agent for running tests in a separate process.
lazy val testAgentProj = (project in file("testing") / "agent").
  settings(
    minimalSettings,
    name := "Test Agent",
    libraryDependencies += testInterface
  )

// Basic task engine
lazy val taskProj = (project in tasksPath).
  dependsOn(controlProj, collectionProj).
  settings(
    testedBaseSettings,
    name := "Tasks"
  )

// Standard task system.  This provides map, flatMap, join, and more on top of the basic task model.
lazy val stdTaskProj = (project in tasksPath / "standard").
  dependsOn (taskProj % "compile;test->test", collectionProj, logProj, ioProj, processProj).
  settings(
    testedBaseSettings,
    name := "Task System",
    testExclusive
  )

// Persisted caching based on SBinary
lazy val cacheProj = (project in cachePath).
  dependsOn (ioProj, collectionProj).
  settings(
    baseSettings,
    name := "Cache",
    libraryDependencies ++= Seq(sbinary, sbtSerialization, scalaReflect.value) ++ scalaXml.value
  )

// Builds on cache to provide caching for filesystem-related operations
lazy val trackingProj = (project in cachePath / "tracking").
  dependsOn(cacheProj, ioProj).
  settings(
    baseSettings,
    name := "Tracking"
  )

// Embedded Scala code runner
lazy val runProj = (project in file("run")).
  dependsOn (ioProj, logProj % "compile;test->test", classpathProj, processProj % "compile;test->test").
  settings(
    testedBaseSettings,
    name := "Run"
  )

// Compiler-side interface to compiler that is compiled against the compiler being used either in advance or on the fly.
//   Includes API and Analyzer phases that extract source API and relationships.
lazy val compileInterfaceProj = (project in compilePath / "interface").
  dependsOn(interfaceProj % "compile;test->test", ioProj % "test->test", logProj % "test->test", /*launchProj % "test->test",*/ apiProj % "test->test").
  settings(
    baseSettings,
    libraryDependencies += scalaCompiler.value % "provided",
    name := "Compiler Interface",
    exportJars := true,
    // we need to fork because in unit tests we set usejavacp = true which means
    // we are expecting all of our dependencies to be on classpath so Scala compiler
    // can use them while constructing its own classpath for compilation
    fork in Test := true,
    // needed because we fork tests and tests are ran in parallel so we have multiple Scala
    // compiler instances that are memory hungry
    javaOptions in Test += "-Xmx1G",
    publishArtifact in (Compile, packageSrc) := true,
    altPublishSettings
  )

// Implements the core functionality of detecting and propagating changes incrementally.
//   Defines the data structures for representing file fingerprints and relationships and the overall source analysis
lazy val compileIncrementalProj = (project in compilePath / "inc").
  dependsOn (apiProj, ioProj, logProj, classpathProj, relationProj).
  settings(
    testedBaseSettings,
    name := "Incremental Compiler"
  )

// Persists the incremental data structures using SBinary
lazy val compilePersistProj = (project in compilePath / "persist").
  dependsOn(compileIncrementalProj, apiProj, compileIncrementalProj % "test->test").
  settings(
    testedBaseSettings,
    name := "Persist",
    libraryDependencies += sbinary
  )

// sbt-side interface to compiler.  Calls compiler-side interface reflectively
lazy val compilerProj = (project in compilePath).
  dependsOn(interfaceProj % "compile;test->test", logProj, ioProj, classpathProj, apiProj, classfileProj,
    logProj % "test->test" /*,launchProj % "test->test" */).
  settings(
    testedBaseSettings,
    name := "Compile",
    libraryDependencies ++= Seq(scalaCompiler.value % Test, launcherInterface),
    unmanagedJars in Test <<= (packageSrc in compileInterfaceProj in Compile).map(x => Seq(x).classpath)
  )

lazy val compilerIntegrationProj = (project in (compilePath / "integration")).
  dependsOn(compileIncrementalProj, compilerProj, compilePersistProj, apiProj, classfileProj).
  settings(
    baseSettings,
    name := "Compiler Integration"
  )

lazy val packageBridgeSource = settingKey[Boolean]("Whether to package the compiler bridge sources in compiler ivy project's resources.")
lazy val compilerIvyProj = (project in compilePath / "ivy").
  dependsOn (ivyProj, compilerProj).
  settings(
    baseSettings,
    name := "Compiler Ivy Integration",
    packageBridgeSource := false,
    resourceGenerators in Compile <+= Def.task {
      if (packageBridgeSource.value) {
        val compilerBridgeSrc = (Keys.packageSrc in (compileInterfaceProj, Compile)).value
        val xsbtiJAR = (Keys.packageBin in (interfaceProj, Compile)).value
        // They are immediately used by the static launcher.
        val included = Set("scala-compiler.jar", "scala-library.jar", "scala-reflect.jar")
        val scalaJars = (externalDependencyClasspath in Compile).value.map(_.data).filter(j => included contains j.getName)
        Seq(compilerBridgeSrc, xsbtiJAR) ++ scalaJars
      }
      else Nil
    }
  )

lazy val scriptedBaseProj = (project in scriptedPath / "base").
  dependsOn (ioProj, processProj).
  settings(
    testedBaseSettings,
    name := "Scripted Framework",
    libraryDependencies ++= scalaParsers.value
  )

lazy val scriptedSbtProj = (project in scriptedPath / "sbt").
  dependsOn (ioProj, logProj, processProj, scriptedBaseProj, interfaceProj).
  settings(
    baseSettings,
    name := "Scripted sbt",
    libraryDependencies += launcherInterface % "provided"
  )

lazy val scriptedPluginProj = (project in scriptedPath / "plugin").
  dependsOn (sbtProj, classpathProj).
  settings(
    baseSettings,
    name := "Scripted Plugin"
  )

// Implementation and support code for defining actions.
lazy val actionsProj = (project in mainPath / "actions").
  dependsOn (classpathProj, completeProj, apiProj, compilerIntegrationProj, compilerIvyProj,
    interfaceProj, ioProj, ivyProj, logProj, processProj, runProj, relationProj, stdTaskProj,
    taskProj, trackingProj, testingProj).
  settings(
    testedBaseSettings,
    name := "Actions"
  )

// General command support and core commands not specific to a build system
lazy val commandProj = (project in mainPath / "command").
  dependsOn(interfaceProj, ioProj, logProj, completeProj, classpathProj, crossProj).
  settings(
    testedBaseSettings,
    name := "Command",
    libraryDependencies ++= Seq(launcherInterface, templateResolverApi, giter8)
  )

// Fixes scope=Scope for Setting (core defined in collectionProj) to define the settings system used in build definitions
lazy val mainSettingsProj = (project in mainPath / "settings").
  dependsOn (applyMacroProj, interfaceProj, ivyProj, relationProj, logProj, ioProj, commandProj,
    completeProj, classpathProj, stdTaskProj, processProj).
  settings(
    testedBaseSettings,
    name := "Main Settings",
    libraryDependencies += sbinary
  )

// The main integration project for sbt.  It brings all of the Projsystems together, configures them, and provides for overriding conventions.
lazy val mainProj = (project in mainPath).
  dependsOn (actionsProj, mainSettingsProj, interfaceProj, ioProj, ivyProj, logProj, logicProj, processProj, runProj, commandProj).
  settings(
    testedBaseSettings,
    name := "Main",
    libraryDependencies ++= scalaXml.value ++ Seq(launcherInterface)
  )

// Strictly for bringing implicits and aliases from subsystems into the top-level sbt namespace through a single package object
//  technically, we need a dependency on all of mainProj's dependencies, but we don't do that since this is strictly an integration project
//  with the sole purpose of providing certain identifiers without qualification (with a package object)
lazy val sbtProj = (project in sbtPath).
  dependsOn(mainProj, compileInterfaceProj, scriptedSbtProj % "test->test").
  settings(
    baseSettings,
    name := "sbt",
    normalizedName := "sbt"
  )

lazy val mavenResolverPluginProj = (project in file("sbt-maven-resolver")).
  dependsOn(sbtProj, ivyProj % "test->test").
  settings(
    baseSettings,
    name := "sbt-maven-resolver",
    libraryDependencies ++= aetherLibs,
    sbtPlugin := true
  )

def scriptedTask: Def.Initialize[InputTask[Unit]] = Def.inputTask {
  val result = scriptedSource(dir => (s: State) => scriptedParser(dir)).parsed
  publishAll.value
  // These two projects need to be visible in a repo even if the default
  // local repository is hidden, so we publish them to an alternate location and add
  // that alternate repo to the running scripted test (in Scripted.scriptedpreScripted).
  (altLocalPublish in interfaceProj).value
  (altLocalPublish in compileInterfaceProj).value
  doScripted((sbtLaunchJar in bundledLauncherProj).value, (fullClasspath in scriptedSbtProj in Test).value,
    (scalaInstance in scriptedSbtProj).value, scriptedSource.value, result, scriptedPrescripted.value,
    scriptedLaunchOpts.value)
}

def scriptedUnpublishedTask: Def.Initialize[InputTask[Unit]] = Def.inputTask {
  val result = scriptedSource(dir => (s: State) => scriptedParser(dir)).parsed
  doScripted((sbtLaunchJar in bundledLauncherProj).value, (fullClasspath in scriptedSbtProj in Test).value,
    (scalaInstance in scriptedSbtProj).value, scriptedSource.value, result, scriptedPrescripted.value,
    scriptedLaunchOpts.value)
}

lazy val publishAll = TaskKey[Unit]("publish-all")
lazy val publishLauncher = TaskKey[Unit]("publish-launcher")

lazy val myProvided = config("provided") intransitive

def allProjects = Seq(interfaceProj, apiProj,
  controlProj, collectionProj, applyMacroProj, processProj, ioProj, classpathProj, completeProj,
  logProj, relationProj, classfileProj, datatypeProj, crossProj, logicProj, ivyProj,
  testingProj, testAgentProj, taskProj, stdTaskProj, cacheProj, trackingProj, runProj,
  compileInterfaceProj, compileIncrementalProj, compilePersistProj, compilerProj,
  compilerIntegrationProj, compilerIvyProj,
  scriptedBaseProj, scriptedSbtProj, scriptedPluginProj,
  actionsProj, commandProj, mainSettingsProj, mainProj, sbtProj, bundledLauncherProj, mavenResolverPluginProj)

def projectsWithMyProvided = allProjects.map(p => p.copy(configurations = (p.configurations.filter(_ != Provided)) :+ myProvided))
lazy val nonRoots = projectsWithMyProvided.map(p => LocalProject(p.id))

def rootSettings = fullDocSettings ++
  Util.publishPomSettings ++ otherRootSettings ++ Formatting.sbtFilesSettings ++
  Transform.conscriptSettings(bundledLauncherProj)
def otherRootSettings = Seq(
  Scripted.scriptedPrescripted := { addSbtAlternateResolver _ },
  Scripted.scriptedLaunchOpts := List("-XX:MaxPermSize=256M", "-Xmx1G"),
  Scripted.scripted <<= scriptedTask,
  Scripted.scriptedUnpublished <<= scriptedUnpublishedTask,
  Scripted.scriptedSource := (sourceDirectory in sbtProj).value / "sbt-test",
  publishAll := {
    val _ = (publishLocal).all(ScopeFilter(inAnyProject)).value
  },
  aggregate in bintrayRelease := false
) ++ inConfig(Scripted.MavenResolverPluginTest)(Seq(
  Scripted.scriptedLaunchOpts := List("-XX:MaxPermSize=256M", "-Xmx1G"),
  Scripted.scripted <<= scriptedTask,
  Scripted.scriptedUnpublished <<= scriptedUnpublishedTask,
  Scripted.scriptedPrescripted := { f =>
    val inj = f / "project" / "maven.sbt"
    if (!inj.exists) {
      IO.write(inj, "addMavenResolverPlugin")
      // sLog.value.info(s"""Injected project/maven.sbt to $f""")
    }
    addSbtAlternateResolver(f)
  }
)) ++ inConfig(Scripted.RepoOverrideTest)(Seq(
  Scripted.scriptedPrescripted := { _ => () },
  Scripted.scriptedLaunchOpts := {
    List("-XX:MaxPermSize=256M", "-Xmx1G", "-Dsbt.override.build.repos=true",
      s"""-Dsbt.repository.config=${ Scripted.scriptedSource.value / "repo.config" }""")
  },
  Scripted.scripted <<= scriptedTask,
  Scripted.scriptedUnpublished <<= scriptedUnpublishedTask,
  Scripted.scriptedSource := (sourceDirectory in sbtProj).value / "repo-override-test"
))

def addSbtAlternateResolver(scriptedRoot: File) = {
  val resolver = scriptedRoot / "project" / "AddResolverPlugin.scala"
  if (!resolver.exists) {
    IO.write(resolver, s"""import sbt._
                          |import Keys._
                          |
                          |object AddResolverPlugin extends AutoPlugin {
                          |  override def requires = sbt.plugins.JvmPlugin
                          |  override def trigger = allRequirements
                          |
                          |  override lazy val projectSettings = Seq(resolvers += alternativeLocalResolver)
                          |  lazy val alternativeLocalResolver = Resolver.file("$altLocalRepoName", file("$altLocalRepoPath"))(Resolver.ivyStylePatterns)
                          |}
                          |""".stripMargin)
  }
}

lazy val docProjects: ScopeFilter = ScopeFilter(
  inAnyProject -- inProjects(sbtRoot, sbtProj, scriptedBaseProj, scriptedSbtProj, scriptedPluginProj, mavenResolverPluginProj),
  inConfigurations(Compile)
)
def fullDocSettings = Util.baseScalacOptions ++ Docs.settings ++ Sxr.settings ++ Seq(
  scalacOptions += "-Ymacro-no-expand", // for both sxr and doc
  sources in sxr := {
    val allSources = (sources ?? Nil).all(docProjects).value
    allSources.flatten.distinct
  }, //sxr
  sources in (Compile, doc) := (sources in sxr).value, // doc
  Sxr.sourceDirectories := {
    val allSourceDirectories = (sourceDirectories ?? Nil).all(docProjects).value
    allSourceDirectories.flatten
  },
  fullClasspath in sxr := (externalDependencyClasspath in Compile in sbtProj).value,
  dependencyClasspath in (Compile, doc) := (fullClasspath in sxr).value
)

/* Nested Projproject paths */
def sbtPath    = file("sbt")
def cachePath  = file("cache")
def tasksPath  = file("tasks")
def launchPath = file("launch")
def utilPath   = file("util")
def compilePath = file("compile")
def mainPath   = file("main")

lazy val safeUnitTests = taskKey[Unit]("Known working tests (for both 2.10 and 2.11)")
lazy val safeProjects: ScopeFilter = ScopeFilter(
  inProjects(mainSettingsProj, mainProj, ivyProj, completeProj,
    actionsProj, classpathProj, collectionProj, compileIncrementalProj,
    logProj, runProj, stdTaskProj, compilerProj, compileInterfaceProj),
  inConfigurations(Test)
)
lazy val otherUnitTests = taskKey[Unit]("Unit test other projects")
lazy val otherProjects: ScopeFilter = ScopeFilter(
  inProjects(interfaceProj, apiProj, controlProj, 
    applyMacroProj,
    // processProj, // this one is suspicious
    ioProj,
    relationProj, classfileProj, datatypeProj,
    crossProj, logicProj, testingProj, testAgentProj, taskProj,
    cacheProj, trackingProj,
    compileIncrementalProj,
    compilePersistProj, compilerProj,
    compilerIntegrationProj, compilerIvyProj,
    scriptedBaseProj, scriptedSbtProj, scriptedPluginProj,
    commandProj, mainSettingsProj, mainProj,
    sbtProj, mavenResolverPluginProj),
  inConfigurations(Test)
)

def customCommands: Seq[Setting[_]] = Seq(
  commands += Command.command("setupBuildScala211") { state =>
    s"""set scalaVersion in ThisBuild := "$scala211" """ ::
      state
  },
  // This is invoked by Travis
  commands += Command.command("checkBuildScala211") { state =>
    s"++ $scala211" ::
      // First compile everything before attempting to test
      "all compile test:compile" ::
      // Now run known working tests.
      safeUnitTests.key.label ::
      state
  },
  safeUnitTests := {
    test.all(safeProjects).value
  },
  otherUnitTests := {
    test.all(otherProjects)
  },
  commands += Command.command("release-sbt-local") { state =>
    "clean" ::
    "so compile" ::
    "so publishLocal" ::
    "reload" ::
    state
  },
  /** There are several complications with sbt's build.
   * First is the fact that interface project is a Java-only project
   * that uses source generator from datatype subproject in Scala 2.10.6.
   *
   * Second is the fact that all subprojects are released with crossPaths
   * turned off for the sbt's Scala version 2.10.6, but some of them are also
   * cross published against 2.11.1 with crossPaths turned on.
   *
   * `so compile` handles 2.10.x/2.11.x cross building.
   */
  commands += Command.command("release-sbt") { state =>
    // TODO - Any sort of validation
    "clean" ::
      "conscript-configs" ::
      "so compile" ::
      "so publishSigned" ::
      "bundledLauncherProj/publishLauncher" ::
      state
  },
  // stamp-version doesn't work with ++ or "so".
  commands += Command.command("release-nightly") { state =>
    "stamp-version" ::
      "clean" ::
      "compile" ::
      "publish" ::
      "bintrayRelease" ::
      state
  },
  // Produces a fat runnable JAR that contains everything needed to use sbt.
  commands += Command.command("install") { state =>
    val packageBridgeSourceKey = packageBridgeSource.key.label
    val compilerIvy = compilerIvyProj.id
    val sbt = sbtProj.id
    s"$compilerIvy/clean" ::
      s"set $packageBridgeSourceKey in $compilerIvy := true" ::
      s"$sbt/assembly" ::
      s"set $packageBridgeSourceKey in $compilerIvy := false" ::
      state
  }
)
