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
    }
}
