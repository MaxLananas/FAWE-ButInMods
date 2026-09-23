plugins {
    java
}

group = "com.maxlananas.fawebim"
version = property("mod_version") as String

allprojects {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
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
