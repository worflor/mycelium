plugins {
    `java-gradle-plugin`
}

group = "dev.woflo.mycelium"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    // era gates: reads a bundle's outside references for the linkage law (docs/eras.md)
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1")
    // the self-tests load task classes, so they run with the Gradle API on their path (no test framework: see SelfTest)
    testImplementation(gradleApi())
}

java {
    // Compiled for 17 so any consumer Gradle JVM (17+) can load it; built on whatever JDK runs Gradle.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

gradlePlugin {
    plugins {
        create("mycelium") {
            id = "dev.woflo.mycelium"
            implementationClass = "dev.woflo.mycelium.gradle.MyceliumPlugin"
            displayName = "Mycelium"
            description = "Imports Mycelium contract models under an artifact-owned namespace, runs contract harnesses as a build gate, and writes an evidence manifest."
        }
        create("myceliumDefaults") {
            id = "dev.woflo.mycelium.defaults"
            implementationClass = "dev.woflo.mycelium.gradle.MyceliumDefaultsPlugin"
            displayName = "Mycelium defaults"
            description = "Declares a mod mycelium block once on the root project of a multi-project build; every project inherits it."
        }
        // The Mycelium repository's own gate; only the Mycelium root applies it, never a consumer.
        create("myceliumRepository") {
            id = "dev.woflo.mycelium.repository"
            implementationClass = "dev.woflo.mycelium.gradle.MyceliumRepositoryPlugin"
            displayName = "Mycelium repository check"
            description = "Checks the Mycelium repository against what it states (registry, packages, copied patterns, eras, documents)."
        }
    }
}

// The plugin's self-tests: a main, labelled checks, exit code = failures (the repository's own style; no test framework).
// They break scratch copies of the Mycelium repository, so they read it from the parent directory.
val selfTest = tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "Runs the plugin's self-tests: era semantics, registry strictness, and every repository check shown to fire."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.woflo.mycelium.gradle.SelfTest"
    // ProjectBuilder (the wiring tests: defaults, derivation, task graph) reflects into the JDK as Gradle's own tests do
    jvmArgs(listOf("java.lang", "java.lang.invoke", "java.util", "java.util.concurrent.atomic", "java.net", "java.nio.charset", "java.io")
        .map { "--add-opens=java.base/$it=ALL-UNNAMED" } + "--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}
tasks.named("check") { dependsOn(selfTest, ":source-wrapper:check") }
tasks.register<JavaExec>("behaviorSelfTest") {
    group = "verification"
    description = "Runs executable callback-analysis counterexamples and safe controls."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.woflo.mycelium.gradle.BehaviorSelfTest"
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("mycelium.testJava").getOrElse("17").toInt())
    }
}
// No test framework, so Gradle's JUnit task has nothing to discover; selfTest runs the test sources instead.
tasks.named<Test>("test") { enabled = false }
