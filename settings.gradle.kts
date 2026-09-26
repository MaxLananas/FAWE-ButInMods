pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://repo.enginehub.org/maven/") { name = "EngineHub" }
    }
}

rootProject.name = "FAWE-BIM"

include("core")
include("fabric")
