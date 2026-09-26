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

    // Read here, on the project, and not inside the task blocks: there the property
    // lookup would be resolved against the task, which does not know it.
    val javaVersion = property("java_version").toString().toInt()

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
    }
}
