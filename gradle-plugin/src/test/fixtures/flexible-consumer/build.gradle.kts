import java.util.zip.ZipFile

// Fixture for docs/decisions/0017 (offer, don't police). The whole declaration is a namespace and a target: the contract
// is found from Mod.java's import, the integration's gate (Gate.java) is found, the pack metadata and the mixin level come
// from the era table, an overlay is chosen by version, and only `jar` carries the evidence. `-Pflex=` plants:
//   failP3 / allowP3 / offP3  the pure layer's clock check at each level (allowP3 = fail with an allowance for Clock.java)
//   leak                      the other mod's API outside its home (I1 fails; compileJava still passes)
//   pureLeak                  a pure layer that names the game (myceliumPureCompile fails; compileJava still passes)
plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

val flex: String = providers.gradleProperty("flex").getOrElse("")

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.flex.mycelium"
    minecraft = "1.21.1"
    pure {
        sources.from(file("kernel"))
        if (flex == "pureLeak") sources.from(file("pure-leak"))
        release = 21
    }
    integration("other", "fixture.flex.compat", "com.example.other")
    packMeta { description = "Flex resources" }
    overlays {
        base(file("res-base"))
        from("26.1", file("res-modern"))
    }
    checks {
        when (flex) {
            "failP3" -> fail("P3")
            "allowP3" -> { fail("P3"); allow("P3", "fixture/flex/kernel/Clock.java", "the fixture's clock is the point") }
            "offP3" -> off("P3")
        }
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
if (flex == "leak") sourceSets.main { java.srcDir("leak") }

val probeJar = tasks.register<Jar>("probeJar") {
    archiveClassifier = "probe"
    from(file("probe"))
}

val verifyFlex = tasks.register("verifyFlex") {
    dependsOn(tasks.jar, probeJar)
    val jar = tasks.jar.flatMap { it.archiveFile }
    val probe = probeJar.flatMap { it.archiveFile }
    val mixin = provider { mycelium.mixinJava }
    doLast {
        ZipFile(jar.get().asFile).use { z ->
            val evidence = z.getInputStream(z.getEntry("META-INF/mycelium/evidence.json")).reader().readText()
            val json = groovy.json.JsonSlurper().parseText(evidence) as Map<*, *>
            val selection = json["contractSelection"] as Map<*, *>
            check(selection["mode"] == "derived") { "the contracts were not derived: $selection" }
            val ids = (json["contracts"] as List<*>).map { (it as Map<*, *>)["id"] }
            check(ids == listOf("random.seeded@1")) { "derived the wrong contracts: $ids" }
            val levels = ((json["checks"] as Map<*, *>)["levels"] as Map<*, *>)["P3"] as Map<*, *>
            val want = if (flex == "failP3" || flex == "allowP3") "fail" else if (flex == "offP3") "off" else "warn"
            check(levels["level"] == want) { "P3 is ${levels["level"]}, not $want" }
            val allowances = ((json["checks"] as Map<*, *>)["allowances"] as List<*>)
            check(allowances.isNotEmpty() == (flex == "allowP3")) { "allowances in the manifest: $allowances" }
            val isolation = (((json["checks"] as Map<*, *>)["reports"] as Map<*, *>)["isolation"] as Map<*, *>)
            check(isolation["checked"] == true) { "isolation did not run before the manifest: $isolation" }
            val gate = ((isolation["integrations"] as List<*>)[0] as Map<*, *>)
            check(gate["gates"] == "derived" && (gate["gateFiles"] as List<*>).any { it.toString().endsWith("fixture/flex/Gate.java") }) {
                "the gate was not derived: $gate"
            }
            val pack = z.getInputStream(z.getEntry("pack.mcmeta")).reader().readText()
            check("\"pack_format\": 48" in pack && "\"supported_formats\"" in pack && "Flex resources" in pack) { "pack.mcmeta is wrong for 1.21.1: $pack" }
            check(z.getEntry("data/flex/schema.json") != null && z.getInputStream(z.getEntry("data/flex/schema.json")).reader().readText().contains("base")) {
                "the overlay for 1.21.1 is not the base"
            }
        }
        ZipFile(probe.get().asFile).use { z ->
            check(z.getEntry("META-INF/mycelium/evidence.json") == null) { "the independent probe carries the artifact's evidence" }
        }
        check(mixin.get() == "JAVA_21") { "mixinJava is ${mixin.get()}" }
    }
}
tasks.check { dependsOn(verifyFlex) }
