import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.Properties

plugins {
    base
    id("fabric-loom") version "1.14-SNAPSHOT" apply false
    kotlin("jvm") version "2.3.10" apply false
}

val releasesDir = rootDir.resolve("build")
val rootResourcesDir = rootDir.resolve("resources")
val sharedResourcesDir = rootDir.resolve("Shared resources")
val sharedMainDir = rootDir.resolve("src/main")

fun loadTargetProperties(projectDir: File): Properties {
    val properties = Properties()
    projectDir.resolve("version.properties").inputStream().use(properties::load)
    return properties
}

configure(
    listOf(
        project(":versions:mc12110"),
        project(":versions:mc12111"),
    ),
) {
    apply(plugin = "base")
    apply(plugin = "fabric-loom")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    val targetProperties = loadTargetProperties(projectDir)

    group = targetProperties.getProperty("maven_group")
    version = targetProperties.getProperty("mod_version")

    extensions.configure<BasePluginExtension> {
        archivesName.set(targetProperties.getProperty("archives_base_name"))
    }

    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }

    dependencies {
        add("minecraft", "com.mojang:minecraft:${targetProperties.getProperty("minecraft_version")}")
        add("mappings", "net.fabricmc:yarn:${targetProperties.getProperty("yarn_mappings")}:v2")
        add("modImplementation", "net.fabricmc:fabric-loader:${targetProperties.getProperty("loader_version")}")
        add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${targetProperties.getProperty("fabric_version")}")
        add("modImplementation", "net.fabricmc:fabric-language-kotlin:${targetProperties.getProperty("fabric_kotlin_version")}")
    }

    extensions.configure<SourceSetContainer> {
        named("main") {
            java.srcDir(sharedMainDir.resolve("java"))
            java.srcDir(projectDir.resolve("java"))

            resources.srcDir(sharedMainDir.resolve("resources"))
            resources.srcDir(projectDir.resolve("resources"))
            resources.srcDir(rootResourcesDir)
            resources.srcDir(sharedResourcesDir)
        }
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        sourceSets.named("main") {
            kotlin.srcDir(sharedMainDir.resolve("kotlin"))
            kotlin.srcDir(projectDir.resolve("kotlin"))

            if (project.name == "mc12110") {
                kotlin.exclude("dev/ryan/shitterlist/ThrowerListEntryProfileScreen.kt")
            }
        }
    }

    tasks.named<ProcessResources>("processResources") {
        inputs.property("version", project.version)
        inputs.property("minecraft_version", targetProperties.getProperty("minecraft_version"))

        filesMatching("fabric.mod.json") {
            expand(
                mapOf(
                    "version" to project.version,
                    "minecraft_version" to targetProperties.getProperty("minecraft_version"),
                ),
            )
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    tasks.named<Jar>("jar") {
        val archivesName = project.extensions.getByType<BasePluginExtension>().archivesName
        from(rootProject.file("LICENSE")) {
            rename { "${it}_${archivesName.get()}" }
        }
    }

    tasks.named<RemapJarTask>("remapJar") {
        destinationDirectory.set(releasesDir)
    }
}
