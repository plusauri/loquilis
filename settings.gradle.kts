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
        // The ui-kit's releases, and nothing else from here (see the ui-kit block at the end).
        exclusiveContent {
            forRepository {
                maven("https://maven.pkg.github.com/plusauri/ui-kit") {
                    name = "UiKitPackages"
                    val token = uiKitPackagesToken()
                    if (token != null) credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: "plusauri-ui-kit-reader"
                        password = token
                    }
                }
            }
            filter { includeGroup("com.plusauri") }
        }
    }
}

rootProject.name = "loquilis"

include(":loquilis-model")
include(":loquilis-agents")
include(":loquilis-store")
include(":loquilis-views")
include(":app")

/**
 * The general controls come from the ui-kit, `plusauri/ui-kit` (package `plusauri.ui`), released to GitHub
 * Packages. `:loquilis-views` names the version it uses (`com.plusauri:ui-kit`, in its build file). The kit
 * depends on Compose only, so it sits outside the rule that Loquilis depends on nothing in Tutilis
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

/**
 * A token that can read `plusauri/ui-kit`'s packages. GitHub Packages asks for one even to download, and
 * only a classic token with `read:packages` works there (not a fine-grained one). First match:
 *
 * 1. the Gradle property `plusauri.packagesToken` (`~/.gradle/gradle.properties`);
 * 2. the env var `PLUSAURI_PACKAGES_TOKEN`;
 * 3. `gh auth token`, so a Mac whose `gh` login has the scope needs nothing else
 *    (`gh auth refresh -h github.com -s read:packages` adds it).
 */
fun uiKitPackagesToken(): String? =
    providers.gradleProperty("plusauri.packagesToken").orNull
        ?: providers.environmentVariable("PLUSAURI_PACKAGES_TOKEN").orNull
        ?: listOf("gh", "/opt/homebrew/bin/gh", "/usr/local/bin/gh").firstNotNullOfOrNull { gh ->
            runCatching {
                providers.exec {
                    commandLine(gh, "auth", "token")
                    isIgnoreExitValue = true
                }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
