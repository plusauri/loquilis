plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// What the version card says about where this build came from.
val buildInfo by tasks.registering {
    val git = { args: List<String> -> providers.exec { commandLine(listOf("git") + args); isIgnoreExitValue = true }.standardOutput.asText.map(String::trim) }
    val commit = git(listOf("rev-parse", "--short", "HEAD"))
    val committed = git(listOf("log", "-1", "--format=%cd", "--date=format:%Y-%m-%d %H:%M"))
    val branch = git(listOf("branch", "--show-current"))
    val changes = git(listOf("status", "--porcelain"))
    val folder = layout.buildDirectory.dir("build-info")
    inputs.property("commit", commit)
    inputs.property("committed", committed)
    inputs.property("branch", branch)
    inputs.property("changed", changes.map(String::isNotEmpty))
    outputs.dir(folder)
    doLast {
        val shown = commit.get() + if (changes.get().isNotEmpty()) " with changes" else ""
        folder.get().file("build-info.properties").asFile.writeText(
            "commit=$shown\ncommitted=${committed.get()}\nbranch=${branch.get()}\n",
        )
    }
}

kotlin {
    jvm()

    jvmToolchain(17)

    sourceSets {
        jvmMain {
            // The window opens the same recorded take the parser is tested on, with no second copy.
            resources.srcDir("../loquilis-agents/src/jvmTest/resources")
            resources.srcDir(buildInfo)

            dependencies {
                implementation(project(":loquilis-views"))
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
        }
    }
}

val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

compose.desktop {
    application {
        mainClass = "loquilis.app.MainKt"
        // -Xdock is a macOS launcher option; another JVM refuses to start with it.
        if (isMac) jvmArgs += "-Xdock:name=Loquilis"
    }
}

// A window loads classes as it needs them, so a rebuild under an open one crashes it. This one runs from
// its own copy of the program, which a later build never touches.
val runStage = layout.buildDirectory.dir("run-stage")
val thisRun = System.currentTimeMillis().toString()

val stageRun by tasks.registering(Sync::class) {
    from(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    into(runStage.map { it.dir(thisRun) })
    doFirst {
        // An open window keeps its folder: it opens each jar only when it first looks in it.
        val running = ProcessHandle.allProcesses().toList().mapNotNull { it.info().commandLine().orElse(null) }
        runStage.get().asFile.listFiles()
            ?.filter { folder -> running.none { folder.path in it } }
            ?.forEach { it.deleteRecursively() }
    }
}

tasks.register<JavaExec>("runWindow") {
    dependsOn(stageRun)
    mainClass = "loquilis.app.MainKt"
    classpath = fileTree(runStage.map { it.dir(thisRun) })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    jvmArgs(listOfNotNull("-Xdock:name=Loquilis".takeIf { isMac }, "-Dcompose.application.configure.swing.globals=true"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
