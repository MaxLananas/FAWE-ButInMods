plugins {
    `java-library`
}

description = "FAWE engine core — platform independent (no Minecraft, no plugin API)."

// The core is intentionally dependency free: it only needs the JDK.
// This is what makes the engine testable head-less (`:core:selfTest`) and keeps
// the Fabric adapter thin.

sourceSets {
    create("selfTest") {
        java.srcDir("src/test/java")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

// The suite in src/test/java is a plain main() with its own asserts, run by the
// selfTest task and checked by :core:verify - there is no JUnit test to
// discover there.  Gradle 9 fails a test task that has sources and no tests, so
// the stock task is turned off rather than left to trip the build.
tasks.named<Test>("test") {
    enabled = false
}

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the built-in engine test-suite (no external test framework needed)."
    group = "verification"
    dependsOn("selfTestClasses")
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.maxlananas.fawebim.core.test.SelfTestMain")
}

val bench by tasks.registering(JavaExec::class) {
    description = "Prints the throughput of the engine's write path (informational)."
    group = "verification"
    dependsOn("selfTestClasses")
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.maxlananas.fawebim.core.test.BenchMain")
}

val commandSpec by tasks.registering(JavaExec::class) {
    description = "Writes build/commands-spec.json, the live registry dump the audits read."
    group = "verification"
    dependsOn("selfTestClasses")
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.maxlananas.fawebim.core.test.CommandDocGenerator")
    args(layout.buildDirectory.file("commands-spec.json").get().asFile.absolutePath,
         layout.projectDirectory.file("../reference/commands-inventory.json").asFile.absolutePath)
}

val checkInventory by tasks.registering(JavaExec::class) {
    description = "Checks every WorldEdit and FAWE command name against the dispatcher's own lookup."
    group = "verification"
    dependsOn("selfTestClasses")
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.maxlananas.fawebim.core.test.StrictInventoryCheck")
    args(layout.projectDirectory.file("../reference/commands-inventory.json").asFile.absolutePath)
    // The upstream inventory is a working file of the audits, not a build input:
    // the reference folder is not in the repository any more, and the check
    // reads it only when it is there.
    onlyIf { layout.projectDirectory.file("../reference/commands-inventory.json").asFile.isFile }
}

tasks.register("verify") {
    group = "verification"
    description = "Compiles the engine, runs the self-tests and checks the command inventory."
    dependsOn(selfTest, checkInventory)
}
