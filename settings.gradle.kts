pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    // Stonecutter: multi-version orchestration. Check latest at https://stonecutter.kikugie.dev/blog/changes/0.9
    id("dev.kikugie.stonecutter") version "0.9.7"

    // Auto-picks obfuscated vs. Mojang-mapped (unobfuscated, 26.1+) Loom setup per targeted version.
    // https://codeberg.org/KikuGie/loom-back-compat
    id("dev.kikugie.loom-back-compat") version "0.4.2"

    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // Add new Minecraft versions here to extend support - nothing else needs touching.
        // See https://stonecutter.kikugie.dev/wiki/start/#choosing-minecraft-versions
        versions("26.1.2", "26.2")
        vcsVersion = "26.1.2"
    }
}

rootProject.name = "skylist"
