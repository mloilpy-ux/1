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
        google() // КРИТИЧЕСКИ ВАЖНО
        mavenCentral()
    }
}

rootProject.name = "DeerPeek"
include(":app")
