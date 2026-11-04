@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // The review window is desktop only: it opens local takes and has no other home yet.
    jvm()

    jvmToolchain(17)

    sourceSets {
        jvmMain {
            // The window opens the same recorded take the parser is tested on, with no second copy.
            resources.srcDir("../loquilis-agents/src/jvmTest/resources")

            dependencies {
                implementation(project(":loquilis-agents"))
                implementation(compose.desktop.currentOs)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.uiTest)
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
