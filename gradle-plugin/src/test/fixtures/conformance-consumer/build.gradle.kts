import java.util.zip.ZipFile

// Fixture for the bridges' conformance cases: a mod whose game tests live in `main` (Somnium's do) must not ship the cases' data
// files. `check` builds the jar and proves it: by default the datapack the world-clock bridge's cases need on 26.1 and later is
// not in the jar, though it is in the compiled resources (the game-test run reads those); `-PshipConformance=true` keeps it in
// (`bridges { shipConformanceResources() }`); `-PclassesOut=true` also keeps the cases' classes out
// (`bridges { conformanceClassesOutOfJar() }`). The bridge's own Minecraft-facing sources are left out of the compile, since
// this build has no game; a stand-in class sits where a conformance class would.
plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

val shipConformance: Boolean = providers.gradleProperty("shipConformance").orElse("false").get().toBoolean()
val classesOut: Boolean = providers.gradleProperty("classesOut").orElse("false").get().toBoolean()

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.conformance.mycelium"
    verify = false
    targetProfile = "conformance-fixture"
    artifactId = "conformance-consumer"
    artifactJars("jar")
    minecraft = "26.3"
    contracts("world.clock@1")
    bridges {
        use("world-clock")      // a bridge is taken by naming it (or by referencing its package in the sources), never for importing its contract
        conformance()
        if (shipConformance) shipConformanceResources()
        if (classesOut) conformanceClassesOutOfJar()
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

// no game on this build's classpath: only the imported contract model and the stand-in compile
tasks.compileJava { exclude("**/bridges/world_clock/**") }

val clock = "data/mycelium_conformance/world_clock/calendar.json"
val timeline = "data/mycelium_conformance/timeline/calendar.json"

val verifyConformance = tasks.register("verifyConformance") {
    dependsOn(tasks.jar, tasks.processResources)
    doLast {
        val compiled = layout.buildDirectory.dir("resources/main").get().asFile
        check(File(compiled, clock).isFile && File(compiled, timeline).isFile) {
            "the compiled resources lack the conformance datapack, so the game-test run could not read it"
        }
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            val data = listOf(clock, timeline).map { jar.getEntry(it) != null }
            check(data.all { it == shipConformance } && data.none { it != shipConformance }) {
                "the jar " + (if (shipConformance) "lacks" else "carries") + " the conformance datapack (ship = $shipConformance): $data"
            }
            // leaving the data out must not leave the (now empty) directories it sat in: no entry should say there was something here
            val strays = jar.entries().asSequence().map { it.name }.filter { it.startsWith("data/mycelium_conformance") }.toList()
            check(shipConformance || strays.isEmpty()) { "the jar carries entries for the conformance data it left out: $strays" }
            val probe = jar.getEntry("fixture/conformance/mycelium/bridges/demo/conformance/Probe.class") != null
            check(probe == !classesOut) { "the jar " + (if (probe) "carries" else "lacks") + " the conformance class (classesOut = $classesOut)" }
            check(jar.getEntry("META-INF/mycelium/evidence.json") != null) { "the jar lacks the evidence manifest" }
        }
    }
}
tasks.check { dependsOn(verifyConformance) }
