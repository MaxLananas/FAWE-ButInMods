pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://repo.enginehub.org/maven/") { name = "EngineHub" }
    }
}

// Minecraft 26.x needs a Java 25 toolchain.  The resolver lets Gradle fetch one
// when the machine running the build does not have it yet, instead of failing
// with "No matching toolchains".
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "FAWE-BIM"

include("core")
include("fabric")
