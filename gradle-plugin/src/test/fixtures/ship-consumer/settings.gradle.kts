// A scratch consumer, used only to prove myceliumPureShip end to end (docs/decisions/0006): a pure layer written for
// 21 with a Java 9+ API call, targeting a Java 8 era, whose jar's classes then run on a real Java 8 JVM. Never built
// by the root's own `./gradlew check` (it is a separate build); run it by hand or from the plugin's self-test notes.
pluginManagement {
    includeBuild("../../../../")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ship-consumer"
