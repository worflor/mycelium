// Fixture for the configuration cache: what a task reads from the Mycelium checkout (a bridge, the era table, the registry) must be
// tracked when the build runs, not computed while it is configured, because the cache stores that value and a later build would
// find myceliumBridgeImport up to date on a bridge that has since been edited. `prove.sh` is the driver: it copies the checkout's
// bridges, eras and registry to a scratch root, builds with the cache on, edits a bridge, builds again, and requires the cache to
// be reused AND the bridge to be imported again with the edit in it. It needs no game and compiles nothing.
plugins {
    java
    id("dev.woflo.mycelium")
}

repositories { mavenCentral() }

mycelium {
    root = file(providers.gradleProperty("myceliumScratch").get())
    namespace = "fixture.cache.mycelium"
    verify = false
    targetProfile = "cache-fixture"
    artifactId = "cache-consumer"
    minecraft = "1.21.1"
    bridges { use("item-carrier") }
}
