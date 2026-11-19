@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The ui-kit release this build uses (GitHub Packages, `plusauri/ui-kit`; settings.gradle.kts). Raising it is
 * how a kit change reaches Loquilis: release the kit (a `v<version>` tag there), then change this line. A kit
 * checkout included from source (`PLUSAURI_UI_KIT_DIR`) wins over it.
 */
val UI_KIT_VERSION = "0.2.0"

kotlin {
    // Desktop only for now: the store's one implementation is JVM.
    jvm()

    jvmToolchain(17)

    sourceSets {
        jvmMain.dependencies {
            api(project(":loquilis-store"))
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api("com.plusauri:ui-kit:$UI_KIT_VERSION")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains:markdown:0.7.18")
        }

        jvmTest {
            // The same recorded take the parser is tested on, with no second copy.
            resources.srcDir("../loquilis-agents/src/jvmTest/resources")

            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(compose.uiTest)
                implementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // A small heap is part of the proof: views that hold a conversation whole cannot pass here.
    maxHeapSize = "384m"
}
