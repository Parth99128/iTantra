pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Vosk Android models/library are published here
        maven { url = uri("https://alphacephei.com/maven") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "iTantra"
include(":app")
