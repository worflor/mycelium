plugins {
    id("dev.woflo.mycelium")
}

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.default17.mycelium"
    verify = false
    pure {
        sources.from(file("src"))
    }
}

tasks.register("verifyDefaultRelease") {
    dependsOn("myceliumPureCompile")
    doLast {
        val classFile = file("build/mycelium/pure/bundle/classes/DefaultReleaseProbe.class")
        val bytes = classFile.readBytes()
        val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        check(major == 61) { "Expected the default Java 17 release (class version 61), found $major" }
        println("Verified default pure release emitted class-file version $major")
    }
}
