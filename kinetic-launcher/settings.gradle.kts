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

rootProject.name = "KineticLauncher"

// :app is the only com.android.application module — the one Android Studio runs.
include(":app")

// Feature/library modules (com.android.library), wired into :app.
include(":launcher-core")
include(":wallpaper-engine")
include(":lockscreen-transition")
include(":security-engine")
