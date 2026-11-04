plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(project(":loquilis-model"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
        }
    }
}

// A test that talks to the real `claude` runs only when asked: LOQUILIS_LIVE=1.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    environment("LOQUILIS_LIVE", System.getenv("LOQUILIS_LIVE") ?: "")
    testLogging.showStandardStreams = System.getenv("LOQUILIS_LIVE") == "1"
}
