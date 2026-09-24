plugins {
    java
}

group = "com.maxlananas.fawebim"
version = property("mod_version") as String

allprojects {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        // Where the layered Parchment mappings of the Fabric project come from.
        maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(property("java_version").toString().toInt())
    }
}
