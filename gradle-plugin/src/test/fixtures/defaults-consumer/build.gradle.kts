// Fixture for docs/decisions/0017, "declare once": the root writes the mod's mycelium block once, and the two projects
// (a version each, as a Stonecutter build has them) inherit it. Each project says only its Minecraft version.
plugins {
    id("dev.woflo.mycelium.defaults")
}

myceliumDefaults {
    root = rootProject.file("../../../../..")
    namespace = "fixture.defaults.mycelium"
    artifactId = "defaults-fixture"
    pure {
        sources.from(rootProject.file("kernels"))
        release = 17
    }
    integration("other", "fixture.defaults.compat", "com.example.other")
    packMeta { description = "Defaults resources" }
    checks {
        warn("I2")
        allow("P3", "fixture/defaults/kernel/Clock.java", "the fixture's clock is the point")
    }
}
