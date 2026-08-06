pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VeloSpot"
include(":app")
// BRouter routing engine, compiled from the pinned `brouter-upstream` submodule
// instead of a bundled JAR (see brouter/README.md).
include(":brouter")
// Baseline Profile producer (Macrobenchmark). Generates the ART baseline/startup
// profile consumed by :app for faster cold start and first map frames.
// See baselineprofile/README.md.
include(":baselineprofile")
