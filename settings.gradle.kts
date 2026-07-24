// Корневой settings-файл Android-проекта Respondo SDK.
// Подключает Gradle-модуль библиотеки respondo-sdk и, при наличии, демо-приложение.
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

rootProject.name = "respondo-android"

include(":respondo-sdk")

// Демо-приложение подключается только если каталог существует (создаётся в фазе демо/QA).
if (file("demo").isDirectory) {
    include(":demo")
}
