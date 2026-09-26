// Fixture for docs/decisions/0006: a pure layer written for Java 21 (`kernel/Greeting.java`, a `List.of` call, Java
// 9+), targeting Minecraft 1.12.2 (era Java 8, `eras/minecraft.json`). `verify = false` so this fixture only exercises
// shipping, not the full contract harness (kept small on purpose; a real consumer verifies its own selection).
plugins {
    java
    id("dev.woflo.mycelium")
}

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.ship.mycelium"
    verify = false
    targetProfile = "java8-fixture"
    artifactId = "ship-fixture"
    minecraft = "1.12.2"
    pure {
        sources.from(file("kernel"))
        release = 21
    }
}
