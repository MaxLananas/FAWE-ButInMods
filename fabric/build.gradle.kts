plugins {
    // The Loom for an unobfuscated Minecraft (26.1 and newer).  The old
    // `fabric-loom` id only knows obfuscated versions and remapping, and there
    // is nothing left to remap: the game ships with Mojang's own names.
    id("net.fabricmc.fabric-loom") version "1.19.1"
    `java-library`
}

description = "FAWE-BIM — Fabric platform adapter for Minecraft 26.2."

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("fabric_loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String

loom {
    // One mixin (swing packets -> "left click air", which FAWE needs for the
    // shatter/erode brushes) and a minimal access widener that mirrors the
    // fields WorldEdit's own adapter widens.
    accessWidenerPath = file("src/main/resources/fawebim.accesswidener")

    runs {
        named("client") {
            client()
            ideConfigGenerated(true)
            runDir = "run"
        }
        named("server") {
            server()
            ideConfigGenerated(true)
            runDir = "run"
        }
    }
}

repositories {
    // Mod Menu is optional at runtime; the compile-only dependency is what makes
    // the client entrypoint type-check, and Loom keeps it out of the jar.
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com/releases")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    // No mappings line: 26.1 and newer are not obfuscated, so there is nothing
    // to remap and the mod configurations (modImplementation, ...) no longer
    // exist — implementation/compileOnly replace them.
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // The Mod Menu screen factory, and the mod itself in a development client so
    // the entrypoint is exercised: neither is required to run the mod.
    val modMenuVersion = property("modmenu_version") as String
    compileOnly("com.terraformersmc:modmenu:$modMenuVersion")
    runtimeOnly("com.terraformersmc:modmenu:$modMenuVersion")

    implementation(project(":core"))
    include(project(":core"))
}

tasks.withType<ProcessResources> {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<Jar> {
    archiveBaseName.set("FAWE-BIM")
    from(rootProject.file("LICENSE.txt")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
}
