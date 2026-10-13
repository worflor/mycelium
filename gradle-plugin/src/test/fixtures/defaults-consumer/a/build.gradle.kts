import java.util.zip.ZipFile

plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

mycelium {
    minecraft = "1.21.1"
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

val verifyInherited = tasks.register("verifyInherited") {
    dependsOn(tasks.jar)
    val jar = tasks.jar.flatMap { it.archiveFile }
    val namespace = mycelium.namespace
    val artifact = mycelium.artifactId
    doLast {
        check(namespace.get() == "fixture.defaults.mycelium") { "namespace was not inherited: ${namespace.get()}" }
        check(artifact.get() == "defaults-fixture") { "artifactId was not inherited" }
        ZipFile(jar.get().asFile).use { z ->
            val evidence = z.getInputStream(z.getEntry("META-INF/mycelium/evidence.json")).reader().readText()
            val json = groovy.json.JsonSlurper().parseText(evidence) as Map<*, *>
            val ids = (json["contracts"] as List<*>).map { (it as Map<*, *>)["id"] }
            check(ids == listOf("random.seeded@1")) { "contracts: $ids" }
            check((json["era"] as Map<*, *>)["minecraft"] == "1.21.1") { "era: ${json["era"]}" }
            val checks = json["checks"] as Map<*, *>
            check((checks["allowances"] as List<*>).size == 1) { "the allowance was not inherited: ${checks["allowances"]}" }
            check(((checks["levels"] as Map<*, *>)["I2"] as Map<*, *>)["level"] == "warn") { "I2 is not warn" }
            val pack = z.getInputStream(z.getEntry("pack.mcmeta")).reader().readText()
            check("\"pack_format\": 48" in pack && "Defaults resources" in pack) { "pack.mcmeta for 1.21.1: $pack" }
        }
    }
}
tasks.check { dependsOn(verifyInherited) }
