plugins {
    kotlin("multiplatform")
    id("com.squareup.wire")
}

kotlin {
    // Wire writes its Kotlin into the tracked src/commonMain/kotlin/loquilis/model/, so a schema change
    // shows up in review. Its repeated-field decoder emits `list!!`, which the compiler calls an
    // unnecessary assertion, and there is no per-file switch for generated code.
    compilerOptions {
        freeCompilerArgs.add("-Xwarning-level=UNNECESSARY_NOT_NULL_ASSERTION:disabled")
    }

    // JVM only for now. The code is all commonMain, so a target is one line away, and declaring one
    // that nothing builds would only be a claim of portability.
    jvm()

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api("com.squareup.wire:wire-runtime:6.2.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation("com.squareup.wire:wire-moshi-adapter:6.2.0")
            implementation("com.squareup.moshi:moshi:1.15.2")
        }
    }
}

wire {
    sourcePath {
        srcDir("protobuf")
    }

    kotlin {
        out = "src/commonMain/kotlin"
    }
}
