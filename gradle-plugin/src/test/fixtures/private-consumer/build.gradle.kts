plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.privateapi.mycelium"
    verify = false
    targetProfile = "private-library-conflict-fixture"
    artifactId = "private-consumer"
    artifactJars("jar")
    minecraft = "1.7.10"
    privateLibrary("org.objectweb.asm", "fixture.privateapi.asm")
}

dependencies {
    compileOnly("org.ow2.asm:asm-all:4.0")
    add("myceliumPrivateLibraries", "org.ow2.asm:asm-tree:9.10.1")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

val verifyPrivateLibrary = tasks.register<JavaExec>("verifyPrivateLibrary") {
    val isolated = tasks.named<dev.woflo.mycelium.gradle.DowngradeJarTask>("myceliumDowngradeJar")
    dependsOn(isolated)
    classpath = files(isolated.flatMap { it.output })
    mainClass = "fixture.CompilerProbe"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(8) }
}

tasks.check { dependsOn(verifyPrivateLibrary) }
