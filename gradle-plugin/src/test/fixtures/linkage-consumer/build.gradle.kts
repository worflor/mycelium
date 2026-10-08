import java.security.MessageDigest
import java.util.zip.ZipFile

// Fixture for docs/decisions/0010: a Fabric artifact claiming Minecraft 1.21 to 1.21.1 on the keystone 1.21.1, whose one class
// calls a method and reads a field of a game class. The build writes the name data itself (a Tiny file for each version, and
// the lock pinning them by SHA-256 at file: urls), so nothing is fetched. `-PlinkageBreak=1.21` leaves the method out of 1.21's
// data: `myceliumLinkage` must refuse the build, naming the reference and the version. `check` proves the unbroken build links
// 1.21 (interpolated until now), keeps 1.21.1 proven and records both in the linkage report.
plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

val plantedBreak: String = providers.gradleProperty("linkageBreak").getOrElse("")
val plantedServerBreak: String = providers.gradleProperty("linkageServerBreak").getOrElse("")
val useServerRuntime: Boolean = providers.gradleProperty("linkageServerRuntime").getOrElse("true") != "false"

abstract class WriteNames : DefaultTask() {
    @get:Input
    abstract val broken: Property<String>

    @get:OutputDirectory
    abstract val dir: DirectoryProperty

    @TaskAction
    fun write() {
        val out = dir.get().asFile.also { it.mkdirs() }
        val entries = listOf("1.21", "1.21.1").map { version ->
            val tiny = buildString {
                append("tiny\t2\t0\tofficial\tintermediary\n")
                append("c\ta\tnet/minecraft/class_1937\n")
                append("\tf\tI\ta\tfield_5\n")
                if (broken.getOrElse("") != version) append("\tm\t(I)I\ta\tmethod_8320\n")
            }
            val file = out.resolve("$version.tiny").also { it.writeText(tiny, Charsets.UTF_8) }
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            "\"$version\": {\"inputs\": [{\"role\": \"mappings\", \"url\": \"${file.toURI()}\", \"sha256\": \"$sha\"}]}"
        }
        out.resolve("names.json").writeText(
            "{\"schema\": 1, \"namespaces\": {\"intermediary\": {\"reads\": \"tiny-flat\", \"owns\": [\"net/minecraft/\"], " +
                "\"versions\": {${entries.joinToString(", ")}}}}}\n", Charsets.UTF_8)
    }
}

val writeNames = tasks.register<WriteNames>("writeNames") {
    broken.set(plantedBreak)
    dir.set(layout.buildDirectory.dir("names"))
}

val serverGame = sourceSets.create("serverGame") {
    java.srcDir(layout.buildDirectory.dir("generated/server-game"))
}
abstract class WriteServerGame : DefaultTask() {
    @get:Input abstract val broken: Property<String>
    @get:OutputDirectory abstract val dir: DirectoryProperty
    @TaskAction fun write() {
        val source = dir.get().file("net/minecraft/class_1937.java").asFile
        source.parentFile.mkdirs()
        source.writeText(buildString {
            append("package net.minecraft;\npublic class class_1937 { public int field_5;\n")
            if (broken.getOrElse("") != "1.21.1") append("public int method_8320(int position) { return position; }\n")
            append("}\n")
        }, Charsets.UTF_8)
    }
}
val writeServerGame = tasks.register<WriteServerGame>("writeServerGame") {
    broken.set(plantedServerBreak)
    dir.set(layout.buildDirectory.dir("generated/server-game"))
}
tasks.named(serverGame.compileJavaTaskName) { dependsOn(writeServerGame) }
val serverJar = tasks.register<Jar>("serverJar") {
    dependsOn(serverGame.classesTaskName)
    from(serverGame.output)
    archiveClassifier.set("dedicated-server")
}

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.linkage.mycelium"
    verify = false
    targetProfile = "linkage-fixture"
    artifactId = "linkage-consumer"
    artifactJars("jar")
    minecraft = "1.21.1"
    range {
        loader = "fabric"
        from = "1.21"
        to = "1.21.1"
        keystones("1.21.1")
        linkage {
            names.set(writeNames.flatMap { it.dir.file("names.json") })
            cache.set(layout.buildDirectory.dir("names-cache"))
            if (useServerRuntime) {
                serverRuntimeArchive.set(serverJar.flatMap { it.archiveFile })
                serverRuntimeNamespace.set("intermediary")
                serverRuntimeOwns.set(listOf("net/minecraft/"))
            }
        }
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// the game's class, as the artifact compiles against it and never ships it
val game = sourceSets.create("game")
dependencies { compileOnly(game.output) }

tasks.processResources {
    val minecraft = mycelium.compat()
    inputs.property("minecraft", minecraft)
    filesMatching("fabric.mod.json") { expand("minecraft" to minecraft) }
}

val verifyLinkage = tasks.register("verifyLinkage") {
    dependsOn("myceliumLinkage")
    val report = layout.buildDirectory.file("mycelium/linkage/linkage.json")
    val jar = tasks.jar.flatMap { it.archiveFile }
    doLast {
        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parseText(report.get().asFile.readText()) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val versions = (parsed["versions"] as List<Map<String, Any>>).associate { it["version"] as String to it["status"] as String }
        check(versions == mapOf("1.21" to "linked", "1.21.1" to "proven")) { "the linkage report says $versions, not 1.21 linked and 1.21.1 proven" }
        @Suppress("UNCHECKED_CAST")
        val server = parsed["serverRuntime"] as Map<String, Any>
        check(server["version"] == "1.21.1" && server["namespace"] == "intermediary"
            && (server["sha256"] as String).matches(Regex("[0-9a-f]{64}"))) {
            "the server runtime inventory identity is missing or incomplete: $server"
        }
        @Suppress("UNCHECKED_CAST")
        val targetLinkage = (parsed["versions"] as List<Map<String, Any>>).first { it["version"] == "1.21.1" }["linkage"] as Map<String, Any>
        check(targetLinkage["serverRuntime"] is String) { "the target linkage row does not report its server inventory: $targetLinkage" }
        ZipFile(jar.get().asFile).use { zip ->
            check(zip.getEntry("net/minecraft/class_1937.class") == null) { "the game's stand-in class was shipped" }
        }
    }
}
tasks.check { dependsOn(verifyLinkage) }
