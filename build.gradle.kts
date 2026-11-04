plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    id("com.squareup.wire") version "6.2.0" apply false
    id("org.jetbrains.compose") version "1.11.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

allprojects {
    group = "com.plusauri.loquilis"
    version = "0.1.0-SNAPSHOT"
}
