plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()

    jvmToolchain(17)

    sourceSets {
        jvmMain {
            // The window opens the same recorded take the parser is tested on, with no second copy.
            resources.srcDir("../loquilis-agents/src/jvmTest/resources")

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

compose.desktop {
    application {
        mainClass = "loquilis.app.MainKt"
        jvmArgs += "-Xdock:name=Loquilis"
    }
}

// A window loads classes as it needs them, so a rebuild under an open one crashes it. This one runs from
// its own copy of the program, which a later build never touches.
val runStage = layout.buildDirectory.dir("run-stage")

val stageRun by tasks.registering(Sync::class) {
    from(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    into(runStage.map { it.dir(System.currentTimeMillis().toString()) })
    doFirst {
        // An open window keeps the files it has open, so its folder can go too.
        runStage.get().asFile.listFiles()?.forEach { it.deleteRecursively() }
    }
}

tasks.register<JavaExec>("runWindow") {
    dependsOn(stageRun)
    mainClass = "loquilis.app.MainKt"
    classpath = files(stageRun.map { fileTree(it.destinationDir) })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    jvmArgs("-Xdock:name=Loquilis", "-Dcompose.application.configure.swing.globals=true")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
