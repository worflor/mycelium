# Eras: every Minecraft version

A mod built on Mycelium has two layers, and they reach back in time differently.

| Layer | What it is | How it reaches another version |
|---|---|---|
| **Pure** | Contract models and a mod's kernels: plain Java, no Minecraft | Written once in modern Java. It holds on every era by law (below) and runs there as downgraded bytecode. It reaches every version for free. |
| **Platform** | The code that touches Minecraft and a loader | Ported where the game changed: Stonecutter across neighbouring versions, a provider per loader. |

The architecture has one goal: **make the platform layer small and the pure layer large**, and prove the pure layer
on every era automatically, so a new version target costs only its platform port.

## Targets drive everything

`eras/minecraft.json` is the data: every Minecraft version from Classic to 26.x, by era, with its Java, its loaders,
how its code is named, and the walls a port crosses (the Flattening, data components...). A consumer names its target
and everything era-shaped follows from the table:

```kotlin
mycelium {
    minecraft = sc.current.version        // this project's era: its gate, and a record in the evidence manifest
}
val requiredJava = JavaVersion.toVersion(mycelium.javaFor(sc.current.version))   // the toolchain, from the same table
```

- **The era gate.** With no `eras(...)` declared, a project runs its own era's gate (`myceliumEraJava21` on 1.21.1,
  `myceliumEraJava25` on 26.3). Adding a 1.12.2 target adds a Java 8 gate. Nothing else changes.
- **Readiness.** `allEras()` runs every Java some era has used (8, 16, 17, 21, 25). Mycelium runs it on itself.
  Somnium's main project (26.3 Fabric) runs it too, so the pure layer is always ready for a version nobody has ported
  yet.
- **Evidence.** `META-INF/mycelium/evidence.json` records the target's era (Minecraft, Java, loaders, names) and every
  era gate's result.

## The pure layer, declared

```kotlin
mycelium {
    pure {
        sources.from(rootProject.file("kernels/src"))    // imported contract models join automatically
        tests.from(rootProject.file("kernels/test"))     // every class named Run with a main is a suite
        release = 21                                     // the Java it is written in
    }
}
```

Its laws, each checked on every build, and each proven by a planted violation that failed the build (2026-09-24):

| Law | Statement | Checked by |
|---|---|---|
| **P1 pure** | It mentions no Minecraft, loader, Mixin or integration package, and none of the artifact's own platform packages (every other main source package). No package is split between the layers. | `myceliumPurity`, on the source (comments and strings ignored) |
| **P2 linkage** | Every class, method and field it reaches outside itself exists on each era's Java, **whether or not a suite reaches the call**. | each era gate: ASM reads the bytecode's outside references; a probe inside the era's own JVM resolves them |
| **P3 deterministic** | It reads no clock, no environment, and no unseeded randomness. It doesn't use `SplittableRandom` (use `random.seeded@1`). | `myceliumPurity` |
| **Stubs** | Where the era is older than the source's release, nothing is left that JvmDowngrader could not backport. Each gap is traced to the calling method. | each era gate, on the downgraded, shaded jar |
| **Behaviour** | Every suite passes on each era's JVM: contract references stay clean, every rejection is caught, the kernels' suites pass. | each era gate |

The pure layer is also compiled **on its own** (`myceliumPureCompile`: harness and kernels, nothing else on the
path). If it compiles there, it is pure in fact, not only by the source scan.

### Why static linkage, not only tests

A test run proves the paths it takes. `java.util.SplittableRandom` broke on Java 8 only because a test happened to
call it. Planting two Java 11/17 calls in a contract model that no test reaches showed the difference:
- The stubs check named the method (`Planted#a -> RandomGenerator.of`): JvmDowngrader only partly backports it.
- The linkage probe caught a hole in **JvmDowngrader itself**: its `HttpClient` backport calls a helper class that
  shading leaves out, so it would crash on Java 8 the first time it ran.

Neither call was reached by any test.

### Own your determinism

The pure layer owns any algorithm whose output is part of its meaning. Seeded randomness is the main one: a seed must
mean the same world on every Java, and after every refactor. **`random.seeded@1`** is that algorithm, once:
- a contract with laws and a rejection matrix (`java.util.Random`, modulo bias, shared state, lenient bounds);
- SplitMix64, bit-identical to `SplittableRandom` (T1's golden checksum), so no seed recorded before changed;
- Mycelium's harness `Seeded` wraps it (`harness/sources.txt`);
- Somnium imports it as `com.example.mymod.mycelium.random_seeded.v1.model.SeededRandom` for its kernels and its game code.

## How an era gate runs (`myceliumEraJava<N>`)

The unit is a **bundle**: `classes/` plus `bundle.properties` (a name, the release it was compiled for, its suite
mains). Each contract `myceliumVerify` builds is one; the pure layer is one. For each bundle:
1. **Bytecode.** As compiled if the era is at least its release. Otherwise JvmDowngrader downgrades it to the era's
   class version and shades in its Java API backports under the artifact's own prefix (`shadePrefix`, default from
   the namespace), so two mods' backports never collide.
2. **Stubs**, then **linkage**, then **behaviour**, as above. The report is `build/mycelium/eras/java<N>/era.json`.

The era's JVM is a Gradle toolchain, provisioned when absent (foojay). JvmDowngrader resolves only when some gate is
older than the bytecode.

**Known tool issue:** JvmDowngrader 2.0.1 races in its shading (a HashMap shared across threads), about 1 run in 4
on this machine. The gate retries once and logs the retry: one failure is noise, two are real.

## Evidence (2026-09-24)

- **Mycelium:** 14 contracts pass on Java 8, 16, 17, 21 and 25 (70 bundle runs), every outside reference resolved and
  every rejection caught. The contract models hold P1 and P3.
- **Somnium:**
  - its pure layer (83 files, 18 suites) passes on Java 8, 16, 17, 21 and 25, with 353 to 357 outside references
    resolved on each;
  - it holds P1 and P3 against 26 platform packages;
  - 75/75 gametests pass on both 26.3 loaders after the switch to `SeededRandom`.

## The platform layer: the map

What a mod must change between versions. The same table drives the gates (`eras/minecraft.json`).

| Minecraft | Java | Loaders | Names in the code | What changes for a mod |
|---|---|---|---|---|
| 26.x | 25 | Fabric, NeoForge | unobfuscated | Where Somnium lives now. |
| 1.20.5 – 1.21.x | 21 | Fabric, NeoForge, Forge | Mojang | **Data components replace item NBT** (1.20.5); holder and item-model reworks (1.21.2, 1.21.4). Somnium's second target. |
| 1.20.2 – 1.20.4 | 17 | Fabric, NeoForge, Forge | Mojang | The configuration network phase; NeoForge splits from Forge. |
| 1.18 – 1.20.1 | 17 | Fabric, Forge, Quilt | Mojang | World height −64..320 (1.18). 1.20.1 is the largest modded population of its time. |
| 1.17.x | 16 | Fabric, Forge | Mojang | Java 16. |
| 1.14 – 1.16.5 | 8 | Fabric, Forge | Mojang from 1.14.4 | Fabric exists. 1.16 adds data-driven dimensions. |
| 1.13 | 8 | Forge, Legacy Fabric, Ornithe | MCP | **The Flattening**: numeric ids become named blocks and states; data packs begin. |
| 1.8 – 1.12.2 | 8 | Forge, Legacy Fabric, Ornithe | MCP | The great modpack era. Block states (1.8). No data packs: content comes from code. |
| 1.0 – 1.7.10 | 8* | Forge, Legacy Fabric, Ornithe | MCP | Numeric ids and metadata. Mycelium the block has existed since Beta 1.9 / 1.0. |
| Beta, Alpha, Classic | 8* | Babric (Beta 1.7.3), Ornithe | Feather | No registries, no events: everything is hand-rolled. |

\* These shipped for Java 6, but they run on Java 8 today, as does every modern loader for them.

The loader columns were compiled on 2026-09-24. Each rung is re-verified against the loaders' own mavens when a
consumer first ports to it.

## Found in practice (running whole suites on old versions)


The rule that follows: a rung is not ported until its whole suite runs there, not only its build.

## Not yet built (in order)

1. **Shipping downgraded jars.** A target whose Java is older than the source's release needs its jar downgraded and
   shaded at build time. The era gate already proves such a jar would hold; the first rung that needs it (1.20.1,
   Java 17, with Somnium written in 21) builds it.
2. **Portability surface.** A report of how much of an artifact touches Minecraft, outside its ports (Somnium's
   `dream/ports/`), per package. It makes the cost of the next rung visible before the port starts, and the aim is to
   shrink it. Stonecutter bridges neighbouring versions; a provider directory per era crosses the walls.
3. **Rungs**, one at a time:
   - 1.20.1 (Fabric, Forge);
   - 1.18.2;
   - 1.16.5 (Java 8);
   - 1.12.2 (Forge, Legacy Fabric; no data packs);
   - 1.7.10;
   - Beta 1.7.3.
