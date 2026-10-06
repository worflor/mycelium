import java.util.zip.ZipFile

// Fixture for docs/decisions/0009: a Fabric artifact claiming Minecraft 1.21 to 1.21.1 on the keystone 1.21.1, whose
// metadata is written from the range. `-PrangeBreak=wall` claims 1.21.4 to 1.21.5 with only the far side of the wall at
// 1.21.5 proved, and `-PrangeBreak=metadata` writes a narrower version into fabric.mod.json than the range claims:
// `myceliumRange` must refuse each by name. `check` proves the unbroken build records the claim in the evidence manifest.
plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

val broken: String? = providers.gradleProperty("rangeBreak").orNull

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.range.mycelium"
    verify = false
    targetProfile = "range-fixture"
    artifactId = "range-consumer"
    artifactJars("jar")
    minecraft = "1.21.1"
    range {
        loader = "fabric"
        from = if (broken == "wall") "1.21.4" else "1.21"
        to = if (broken == "wall") "1.21.5" else "1.21.1"
        keystones(if (broken == "wall") "1.21.5" else "1.21.1")
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// `-PsharedOutput` puts compiled classes and processed resources in one directory, as ForgeGradle does for Forge 1.21 and
// later: `myceliumRange` reads resources from a directory compileJava also writes, so it must run after compileJava.
if (providers.gradleProperty("sharedOutput").isPresent) {
    val shared = layout.buildDirectory.dir("sourceSets/main")
    sourceSets.main {
        java.destinationDirectory.set(shared)
        output.setResourcesDir(shared)
    }
}

tasks.processResources {
    val minecraft = if (broken == "metadata") "1.21.1" else mycelium.compat()
    inputs.property("minecraft", minecraft)
    filesMatching("fabric.mod.json") { expand("minecraft" to minecraft) }
}

val verifyRangeEvidence = tasks.register("verifyRangeEvidence") {
    dependsOn(tasks.jar)
    doLast {
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            val evidence = jar.getInputStream(jar.getEntry("META-INF/mycelium/evidence.json")).reader().readText()
            check("\"schema\": 3" in evidence) { "the manifest is not schema 3" }
            check("\"status\": \"proven\"" in evidence && "\"status\": \"interpolated\"" in evidence) {
                "the manifest does not record the keystone as proven and 1.21 as interpolated"
            }
            check("\">=1.21 <=1.21.1\"" in evidence) { "the manifest does not carry the metadata the range wrote" }
            check("\"linkage\"" in evidence) { "the manifest does not say what linkage was not run" }
        }
    }
}
tasks.check { dependsOn(verifyRangeEvidence) }
