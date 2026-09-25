plugins {
    id("fabric-loom") version "1.11.8"
    `java-library`
}

description = "FAWE-BIM — Fabric platform adapter for Minecraft 1.21.10."

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("fabric_loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String

loom {
    // One mixin (swing packets -> "left click air", which FAWE needs for the
    // shatter/erode brushes) and a minimal access widener that mirrors the two
    // fields WorldEdit's own Fabric adapter widens.
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
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraftVersion:${property("parchment_mappings_version")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // The Mod Menu screen factory, and the mod itself in a development client so
    // the entrypoint is exercised: neither is required to run the mod.
    val modMenuVersion = property("modmenu_version") as String
    modCompileOnly("com.terraformersmc:modmenu:$modMenuVersion")
    modLocalRuntime("com.terraformersmc:modmenu:$modMenuVersion")

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
