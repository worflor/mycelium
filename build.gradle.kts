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
    // every Java some Minecraft era runs on (eras/minecraft.json): 8, 16, 17, 21, 25
    allEras()
    // the contract models are a pure layer too: no platform (P1), no clock or unseeded chance (P3)
    pure {
        sources.from(fileTree("contracts") { include("**/model/**/*.java") })
    }
}
