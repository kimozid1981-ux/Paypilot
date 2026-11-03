rootProject.name = "PayPilot"

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
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":core")
include(":server")
// The app module needs Google Maven (Android, AndroidX). Skip it with -PcoreOnly=true where that repo is unreachable.
if (providers.gradleProperty("coreOnly").orNull != "true") {
    include(":composeApp")
} else {
    rootProject.buildFileName = "build-core.gradle.kts"
}
