pluginManagement {
    plugins {
        kotlin("jvm") version "2.0.21"
        kotlin("android") version "2.0.21"
        id("com.android.application") version "8.7.3"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "futmondo"
include(":core")
// -PcoreOnly compila y prueba solo la lógica (JVM), sin Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
