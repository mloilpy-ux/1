pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Этот флаг как раз и требует, чтобы репозитории были только здесь
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) 
    repositories {
        google()          // Сюда пойдут зависимости Google AI SDK
        mavenCentral()    // Сюда пойдет Seismic и остальные либы
    }
}

rootProject.name = "DeerPeek"
include(":app")
