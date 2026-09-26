// Mycelium checks itself. `./gradlew check` runs everything the repository promises:
//   - myceliumRepository: the repository against what it states (registry, package shapes, copied patterns, the era
//     and loader tables, documents); docs/decisions/0004-the-repository-checks-itself.md
//   - myceliumVerify: every contract harness in the registry (the plugin, dogfooded)
//   - myceliumPurity: the contract models are a pure layer (no platform, no clock or unseeded chance)
//   - myceliumEras: every harness on every Java some Minecraft era runs on
//   - the plugin's own self-tests (gradle-plugin: selfTest)
// No Java plugin is applied here, so nothing is imported; this root is a verification project.
plugins {
    id("dev.woflo.mycelium")
    id("dev.woflo.mycelium.repository")
}

mycelium {
    root = layout.projectDirectory
    // empty contract list = verify every entry in contracts/registry.json
    targetProfile = "design-time"
    artifactId = "mycelium"
    artifactVersion = "0.4"
    // every Java some Minecraft era runs on (eras/minecraft.json)
    allEras()
    // the contract models are a pure layer too: no platform (P1), no clock or unseeded chance (P3)
    pure {
        sources.from(fileTree("contracts") { include("**/model/**/*.java") })
    }
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("gradle-plugin").task(":check"))
}
