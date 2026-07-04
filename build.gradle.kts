import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.Properties

plugins {
    base
    id("net.fabricmc.fabric-loom") version "1.17.13" apply false
    kotlin("jvm") version "2.4.0" apply false
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
        project(":versions:mc2612"),
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

    val loom = extensions.getByType<LoomGradleExtensionAPI>()
    loom.noIntermediateMappings()

    dependencies {
        add("minecraft", "com.mojang:minecraft:${targetProperties.getProperty("minecraft_version")}")
        add("mappings", "net.fabricmc:intermediary:${targetProperties.getProperty("intermediary_version")}:v2")
        add("implementation", "net.fabricmc:fabric-loader:${targetProperties.getProperty("loader_version")}")
        add("implementation", "net.fabricmc.fabric-api:fabric-api:${targetProperties.getProperty("fabric_version")}")
        add("implementation", "net.fabricmc:fabric-language-kotlin:${targetProperties.getProperty("fabric_kotlin_version")}")
        add("compileOnly", "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
        add("runtimeOnly", "org.ow2.asm:asm:9.10.1")
        add("runtimeOnly", "org.ow2.asm:asm-analysis:9.10.1")
        add("runtimeOnly", "org.ow2.asm:asm-commons:9.10.1")
        add("runtimeOnly", "org.ow2.asm:asm-tree:9.10.1")
        add("runtimeOnly", "org.ow2.asm:asm-util:9.10.1")
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
        jvmToolchain(25)
        sourceSets.named("main") {
            kotlin.srcDir(sharedMainDir.resolve("kotlin"))
            kotlin.srcDir(projectDir.resolve("kotlin"))
        }
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
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
        options.release.set(25)
    }

    tasks.named<Jar>("jar") {
        val archivesName = project.extensions.getByType<BasePluginExtension>().archivesName
        destinationDirectory.set(releasesDir)
        from(rootProject.file("LICENSE")) {
            rename { "${it}_${archivesName.get()}" }
        }
    }

    tasks.named<RemapJarTask>("remapJar") {
        destinationDirectory.set(releasesDir)
    }
}
