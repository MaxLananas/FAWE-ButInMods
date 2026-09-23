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

val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the built-in engine test-suite (no external test framework needed)."
    group = "verification"
    dependsOn("selfTestClasses")
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.fawebutinmods.core.test.SelfTestMain")
}

val genDocs by tasks.registering(JavaExec::class) {
    description = "Regenerates docs/COMMANDS.md from the command registry."
    group = "documentation"
    dependsOn("selfTestClasses")
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.fawebutinmods.core.test.CommandDocGenerator")
    args(layout.projectDirectory.file("../docs/COMMANDS.md").asFile.absolutePath,
         layout.projectDirectory.file("../docs/commands-spec.json").asFile.absolutePath)
}

val checkInventory by tasks.registering(JavaExec::class) {
    description = "Checks every WorldEdit and FAWE command name against the dispatcher's own lookup."
    group = "verification"
    dependsOn("selfTestClasses")
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.fawebutinmods.core.test.StrictInventoryCheck")
    args(layout.projectDirectory.file("../docs/commands-inventory.json").asFile.absolutePath)
}

tasks.register("verify") {
    group = "verification"
    description = "Compiles the engine, runs the self-tests and checks the command inventory."
    dependsOn(selfTest, checkInventory)
}
