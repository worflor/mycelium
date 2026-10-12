import java.util.zip.ZipFile

// Fixture for docs/decisions/0012: a consumer with one item presentation program. `check` builds the jar and proves it
// carries the era's artefact and the plan the runtime adapter reads: an item definition on 1.21.4 (the default), an item model
// with overrides with `-PpresentationMinecraft=1.20.1`. A selection the committed lock lacks fails the build with the lines to
// add; `./gradlew myceliumPresentationLock` appends them to src/main/presentation/presentation.lock.json.
plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

val target: String = providers.gradleProperty("presentationMinecraft").orElse("1.21.4").get()

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.presentation.mycelium"
    verify = false
    targetProfile = "presentation-fixture"
    artifactId = "presentation-consumer"
    artifactJars("jar")
    minecraft = target
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

val verifyPresentation = tasks.register("verifyPresentation") {
    dependsOn(tasks.jar)
    doLast {
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            val artefact = if (target == "1.21.4") "assets/fixture/items/status_tool.json" else "assets/fixture/models/item/status_tool.json"
            check(jar.getEntry(artefact) != null) { "the jar lacks $artefact" }
            check(jar.getEntry("META-INF/mycelium/presentation/fixture/status_tool.plan.json") != null) { "the jar lacks the plan" }
            val text = jar.getInputStream(jar.getEntry(artefact)).reader().readText()
            check(("minecraft:select" in text) == (target == "1.21.4")) { "$artefact is not the $target realization" }
        }
    }
}
tasks.check { dependsOn(verifyPresentation) }
