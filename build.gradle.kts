import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    // Picks obfuscated-mappings Loom or Mojang-mapped Loom automatically per targeted version.
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm") version "2.4.0"
}

// DO NOT set group = ...! loom-back-compat manages it.
version = "${property("mod.version")}-${sc.current.version}"
base.archivesName.set(property("mod.id") as String)

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // No-op on already-unobfuscated versions (26.1+); resolves+applies Mojang mappings on older,
    // obfuscated ones. This is the "adapt mappings automatically per version" mechanism.
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"] as String}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("deps.fabric_kotlin")}")

    // Optional integration: compiled against, never required at runtime - Skylist works fine
    // without ModMenu installed. modLocalRuntime pulls it into `runClient` only, not the shipped jar.
    modCompileOnly("com.terraformersmc:modmenu:${property("deps.modmenu")}")
    modLocalRuntime("com.terraformersmc:modmenu:${property("deps.modmenu")}")
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(25)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

loom {
    runConfigs.all {
        runDirectory = rootProject.file("run") // shared between versions
    }
}

tasks.processResources {
    val modId = project.property("mod.id") as String
    val modName = project.property("mod.name") as String
    val modVersion = project.version.toString()
    val minecraftCompat = sc.properties["mod.mc_compat"] as String

    inputs.property("id", modId)
    inputs.property("name", modName)
    inputs.property("version", modVersion)
    inputs.property("minecraft_compat", minecraftCompat)

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "id" to modId,
                "name" to modName,
                "version" to modVersion,
                "minecraft" to minecraftCompat,
            ),
        )
    }
}

val releasesDir = rootProject.rootDir.resolve("build")

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_${base.archivesName.get()}" }
    }
}

// loomx.modJar resolves to whichever task actually produces the final mod jar for this version's
// variant (remapped or not) - safer than guessing a task name like "remapJar" that may not exist
// on every mapping path.
loomx.modJar {
    destinationDirectory.set(releasesDir)
}
