pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "skylist"

include(":versions:mc12110")
project(":versions:mc12110").projectDir = file("versions/1.21.10")

include(":versions:mc12111")
project(":versions:mc12111").projectDir = file("versions/1.21.11")
