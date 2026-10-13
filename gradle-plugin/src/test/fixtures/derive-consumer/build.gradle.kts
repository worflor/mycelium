import java.util.zip.ZipFile

// Fixture for the contract derivation in a CLEAN build with the configuration cache on (docs/plugin.md). The contracts come from:
//   src/main/java            a fully qualified reference with no import (random.seeded@1)
//   kernel (the pure layer)  a fully qualified reference (world.clock@1)
//   stonecutterGenerate      a task of that name writes a source directory nothing declares as its producer, as Stonecutter
//                            does, with an import and a static import (data.catalog@1)
//   generateCatalog          a task whose output is declared a source directory (data.catalog@1)
// None of the generated sources exist when the build is configured, so a list derived then finds only the first two.
plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.derive.mycelium"
    minecraft = "1.21.1"
    pure {
        sources.from(file("kernel"))
        release = 21
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

val swapped = layout.buildDirectory.dir("generated/swapped/java")
val stonecutterGenerate = tasks.register("stonecutterGenerate") {
    val out = swapped
    outputs.upToDateWhen { false }
    doLast {
        val dir = out.get().asFile.resolve("fixture/derive").also { it.mkdirs() }
        dir.resolve("Swapped.java").writeText(
            """
            package fixture.derive;
            import fixture.derive.mycelium.data_catalog.v1.model.Catalog;
            import static fixture.derive.mycelium.data_catalog.v1.model.DataCatalog.*;
            class Swapped { Weather w; }
            """.trimIndent()
        )
    }
}
sourceSets.main { java.srcDir(swapped) }
tasks.compileJava { dependsOn(stonecutterGenerate) }

val generateCatalog = tasks.register("generateCatalog") {
    val out = layout.buildDirectory.dir("generated/catalog/java")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("fixture/derive").also { it.mkdirs() }
        dir.resolve("Cat.java").writeText(
            "package fixture.derive;\nclass Cat { fixture.derive.mycelium.data_catalog.v1.model.Snapshot s; }\n"
        )
    }
}
sourceSets.main { java.srcDir(generateCatalog.map { it.outputs.files.singleFile }) }

val verifyDerive = tasks.register("verifyDerive") {
    dependsOn(tasks.jar)
    val jar = tasks.jar.flatMap { it.archiveFile }
    doLast {
        ZipFile(jar.get().asFile).use { z ->
            val evidence = z.getInputStream(z.getEntry("META-INF/mycelium/evidence.json")).reader().readText()
            val json = groovy.json.JsonSlurper().parseText(evidence) as Map<*, *>
            check((json["contractSelection"] as Map<*, *>)["mode"] == "derived") { "not derived: ${json["contractSelection"]}" }
            val ids = (json["contracts"] as List<*>).map { (it as Map<*, *>)["id"] as String }.sorted()
            val want = listOf("data.catalog@1", "random.seeded@1", "data.catalog@1", "world.clock@1")
            check(ids == want) { "derived $ids, wanted $want" }
        }
    }
}
tasks.check { dependsOn(verifyDerive) }
