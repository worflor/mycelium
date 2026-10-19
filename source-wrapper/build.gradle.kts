import java.security.MessageDigest

plugins { java }

group = "dev.woflo.mycelium"
version = "1.0.0"
repositories { mavenCentral() }
dependencies { implementation("com.google.code.gson:gson:2.11.0") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach { options.release = 17; options.encoding = "UTF-8" }
tasks.jar {
    archiveBaseName = "mycelium-wrapper"
    manifest { attributes("Main-Class" to "dev.woflo.mycelium.source.Main", "Implementation-Version" to project.version) }
    from(configurations.runtimeClasspath.get().map { zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/versions/**", "module-info.class")
    }
    from("LICENSES") { into("META-INF/licenses") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.named<Jar>("sourcesJar") {
    archiveBaseName = "mycelium-wrapper"
    from("build.gradle.kts", "README.md")
    from("consumer") { into("consumer") }
    from("LICENSES") { into("LICENSES") }
    from("src") { into("src") }
    from("../docs/source-lifecycle.md") { into("docs") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val checksum = tasks.register("wrapperChecksum") {
    dependsOn(tasks.jar)
    val artifact = tasks.jar.flatMap { it.archiveFile }
    val output = layout.buildDirectory.file("wrapper.sha256")
    inputs.file(artifact); outputs.file(output)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.get().asFile.readBytes())
        output.get().asFile.writeText(digest.joinToString("") { "%02x".format(it) } + "  mycelium-wrapper.jar\n")
    }
}
tasks.register<Zip>("consumerBundle") {
    dependsOn(tasks.jar, tasks.named("sourcesJar"), checksum)
    archiveBaseName = "mycelium-consumer"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("consumer") { include("mycelium"); filePermissions { unix("rwxr-xr-x") } }
    from("consumer") { include("mycelium.cmd", ".gitattributes") }
    into("tools/mycelium") {
        from(tasks.jar) { rename { "mycelium-wrapper.jar" } }
        from(tasks.named("sourcesJar")) { rename { "mycelium-wrapper-sources.jar" } }
        from("consumer") { exclude("mycelium", "mycelium.cmd", ".gitattributes", "tools.gitattributes") }
        from("consumer/tools.gitattributes") { rename { ".gitattributes" } }
        from(layout.buildDirectory.file("wrapper.sha256"))
        from("LICENSES") { into("LICENSES") }
        from("README.md")
        from("../docs/source-lifecycle.md")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.register<JavaExec>("selfTest") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.woflo.mycelium.source.LifecycleTest"
}
tasks.register<JavaExec>("consumerTest") {
    dependsOn("consumerBundle", "testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.woflo.mycelium.source.ConsumerTest"
    args(tasks.named<Zip>("consumerBundle").flatMap { it.archiveFile }.get().asFile.absolutePath,
        rootProject.layout.projectDirectory.file("../gradle/wrapper/gradle-wrapper.jar").asFile.absolutePath,
        layout.buildDirectory.get().asFile.absolutePath)
}
tasks.check { dependsOn("selfTest", "consumerTest") }
tasks.test { enabled = false }
