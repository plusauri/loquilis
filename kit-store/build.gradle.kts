plugins {
    kotlin("multiplatform")
}

kotlin {
    // The Store contract is commonMain. Only its SQLite implementation is JVM.
    jvm()

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(project(":kit-agents"))
        }

        jvmMain.dependencies {
            implementation("org.xerial:sqlite-jdbc:3.50.3.0")
            implementation("org.slf4j:slf4j-nop:2.0.17")
            implementation("com.squareup.wire:wire-moshi-adapter:6.2.0")
            implementation("com.squareup.moshi:moshi:1.15.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest {
            // The same recorded take the parser is tested on, with no second copy.
            resources.srcDir("../kit-agents/src/jvmTest/resources")

            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // A small heap is part of the proof: a store that loads a conversation whole cannot pass here.
    maxHeapSize = "256m"
    environment("LOQUILIS_SCALE", System.getenv("LOQUILIS_SCALE") ?: "")
    testLogging.showStandardStreams = true
}
