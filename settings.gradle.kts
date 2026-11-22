pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "loquilis"

include(":loquilis-model")
include(":loquilis-agents")
include(":loquilis-store")
include(":loquilis-views")
include(":app")

/**
 * The general controls come from the ui-kit, `plusauri/ui-kit` (package `plusauri.ui`), released to Maven
 * Central and read with no token. `:loquilis-views` names the version it uses (`com.plusauri:ui-kit`, in its
 * build file). The kit depends on Compose only. It is the one dependency on another Plusauri project
 * (`doc/decisions/0001-dependency-direction.md`).
 *
 * **Working on the kit.** Set the Gradle property `plusauri.uiKitDir`, or the env var `PLUSAURI_UI_KIT_DIR`,
 * to a kit checkout (relative paths resolve from this folder). The kit then joins as a composite build and
 * Gradle builds it from that source in place of the release. Release the kit and raise the version in
 * `:loquilis-views` before the Loquilis change that needs it lands.
 */
val uiKitDir: File? = (providers.gradleProperty("plusauri.uiKitDir").orNull
    ?: providers.environmentVariable("PLUSAURI_UI_KIT_DIR").orNull)
    ?.let { named ->
        settingsDir.resolve(named).canonicalFile.also {
            if (!File(it, "settings.gradle.kts").isFile) throw GradleException(
                "plusauri.uiKitDir / PLUSAURI_UI_KIT_DIR names $it, which is not a ui-kit checkout " +
                    "(git clone git@github.com:plusauri/ui-kit.git <dir>)",
            )
        }
    }
if (uiKitDir != null) includeBuild(uiKitDir) { name = "ui-kit" }
