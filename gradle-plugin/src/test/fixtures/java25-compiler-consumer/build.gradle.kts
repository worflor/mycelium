plugins {
    java
    id("dev.woflo.mycelium")
}

check(JavaVersion.current() == JavaVersion.VERSION_21) {
    "This fixture must run with Gradle on Java 21; current JVM is ${JavaVersion.current()}"
}

val pathStressSources = layout.buildDirectory.dir("generated/path stress/source files")
val generatePathStressSources = tasks.register("generatePathStressSources") {
    outputs.dir(pathStressSources)
    doLast {
        val sourceRoot = pathStressSources.get().asFile
        val projectRoot = layout.projectDirectory.asFile.canonicalFile.toPath()
        val buildRoot = layout.buildDirectory.get().asFile.canonicalFile.toPath()
        val generatedRoot = sourceRoot.canonicalFile.toPath()
        check(generatedRoot != projectRoot && generatedRoot != buildRoot
                && generatedRoot.startsWith(projectRoot) && generatedRoot.startsWith(buildRoot)) {
            "Refusing to clean generated sources outside this fixture's build directory: $generatedRoot"
        }
        project.delete(generatedRoot.toFile())
        repeat(399) { index ->
            val name = "PathStress%03d".format(index)
            val source = sourceRoot.resolve("fixture/java25/mycelium/pathstress/$name.java")
            source.parentFile.mkdirs()
            source.writeText("package fixture.java25.mycelium.pathstress; final class $name {}\n")
        }
    }
}

mycelium {
    root = rootProject.file("../../../../..")
    namespace = "fixture.java25.mycelium"
    verify = false
    pure {
        sources.from(file("kernel with spaces"))
        sources.from(generatePathStressSources)
        release = 25
    }
}
