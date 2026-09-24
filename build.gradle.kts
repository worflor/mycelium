// Mycelium dogfoods its own Gradle plugin: `./gradlew myceliumVerify` runs every contract harness in the
// registry. No Java plugin is applied here, so nothing is imported; this root is a verification project.
plugins {
    id("dev.woflo.mycelium")
}

mycelium {
    root = layout.projectDirectory
    // empty contract list = verify every entry in contracts/registry.json
    targetProfile = "design-time"
    artifactId = "mycelium"
    artifactVersion = "0.4"
    // every Java a Minecraft era runs on: 8 (up to 1.16), 16 (1.17), 17 (1.18-1.20.4), 21 (1.20.5-1.21), 25 (26.x)
    eras(8, 16, 17, 21, 25)
}
