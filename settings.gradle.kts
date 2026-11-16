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

// Template leftover. The installed app is com.crrpendlebury.barrierandroid
// (launcher label BarrierAndroid). Do not rename call sites to FoodDiary,
// and do not treat this string as the product name. Renaming the Gradle
// project is safe and does not change the application id.
rootProject.name = "FoodDiary"
include(":app")
