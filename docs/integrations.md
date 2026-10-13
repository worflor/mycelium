# Optional integrations, isolated

A mod that works *with* another mod when it is installed and *without* it when it is not keeps that promise only if the
other mod's classes are never loaded when it is absent. One stray `import` in the wrong class and the artifact crashes
on every server that doesn't have the other mod. That is a property of the source's shape, so Mycelium checks it at
build time instead of hoping.

## The law (`myceliumIsolation`, runs before `compileJava` and in `check`)

| Law | Statement |
|---|---|
| I1 confined | Only sources inside an integration's **home package** mention the other mod's API packages. |
| I2 unreferenced | Nothing outside the home package mentions it, except the integration's declared **gates**: the one place that checks the mod is loaded, then calls in. |

Comments are ignored (a doc comment may name anything). The report is `build/mycelium/isolation/isolation.json`, and a
violation is reported with the file and the package: I1 fails the build, I2 warns by default (`mycelium { checks { } }` sets either).

```kotlin
mycelium {
    //          name        home package                     the other mod's API packages
    integration("voicechat", "com.example.mymod.voice",      "de.maxhenkel.voicechat")
    integration("cobblemon", "com.example.mymod.compat.cobblemon", "com.cobblemon",
                "com/example/mymod/compat/Integrations.java")   // a gate: the one file that calls in
}
```

A declaration is validated where it is written and again where the task reads it. It needs a name, the home package
and every API package must be Java package names, and it must name at least one API package. An integration that
could keep nothing out is refused rather than passed.

## Written once, built for every version that has the mod

With Stonecutter, an integration is declared per version and compiled only where its mod exists:

1. **Declare it per version.** `stonecutter.properties.toml` gets `deps.<mod> = "<version>"` under each Minecraft
   version the mod supports. Declare it per loader and version (`[fabric."1.21.1"]`) where the other mod does not
   ship for every loader, so a loader it lacks never compiles the integration.
2. **One constant per integration.** `stonecutter.gradle.kts` sets `constants[mod] = properties.getOrNull("deps.$mod")
   != null`.
3. **Guard the code.** The integration's code sits under `//? if <mod> {`, so on other versions it does not exist at
   all. Use line comments inside it: nested block comments do not survive the toggle.
4. **Compile-only dependencies.** Loader build scripts add the mod as `compileOnly` / `modCompileOnly`, when
   `findProperty("deps.<mod>")` is set.
5. **The gate.** The gate calls in under the same guard, after the loader says the mod is loaded.

The integration is written once. It builds for every loader of every version the other mod supports, and isolation
proves each of those builds loads nothing it shouldn't. Two shapes cover most integrations: a plugin API the other
mod discovers (it calls you; the loader's entrypoint or annotation finds the home package), and a runtime check behind
a gate (you call it, only after the loader says it is present). `CHANGELOG.md` records where these were first proven.
