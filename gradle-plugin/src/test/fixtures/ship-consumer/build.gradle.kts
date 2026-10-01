import java.util.zip.ZipFile

// Fixture for docs/decisions/0006: a pure layer written for Java 21 (`kernel/Greeting.java`, a `List.of` call, Java
// 9+), targeting Minecraft 1.12.2 (era Java 8, `eras/minecraft.json`). `verify = false` so this fixture only exercises
// shipping, not the full contract harness (kept small on purpose; a real consumer verifies its own selection).
plugins {
    java
    id("dev.woflo.mycelium")
}

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.ship.mycelium"
    verify = false
    targetProfile = "java8-fixture"
    artifactId = "ship-fixture"
    artifactJars("jar")
    minecraft = "1.12.2"
    pure {
        sources.from(file("kernel"))
        release = 21
    }
}

val probeJar = tasks.register<Jar>("probeJar") {
    archiveClassifier = "probe"
    from(file("probe"))
}
val verifyArtifactAttachment = tasks.register("verifyArtifactAttachment") {
    dependsOn(tasks.jar, probeJar)
    doLast {
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            check(jar.getEntry("fixture/ship/kernel/Greeting.class") != null) { "production jar lost its pure layer" }
            check(jar.getEntry("META-INF/mycelium/evidence.json") != null) { "production jar lost its evidence" }
        }
        ZipFile(probeJar.get().archiveFile.get().asFile).use { jar ->
            check(jar.getEntry("fixture/ship/kernel/Greeting.class") == null) { "independent probe contains the production pure layer" }
            check(jar.getEntry("META-INF/mycelium/evidence.json") == null) { "independent probe carries another artifact's evidence" }
            check(jar.getEntry("probe.txt") != null) { "probe's own contents are missing" }
        }
    }
}
tasks.check { dependsOn(verifyArtifactAttachment) }
