pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://repo.enginehub.org/maven/") { name = "EngineHub" }
    }
}

rootProject.name = "fawe-but-in-mods"

include("core")
include("fabric")
