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

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraftVersion:${property("parchment_mappings_version")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

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
