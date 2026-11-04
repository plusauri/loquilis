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
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
