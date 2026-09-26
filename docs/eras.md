# Eras: every Minecraft version

A mod built on Mycelium has two layers, and they reach back in time differently.

| Layer | What it is | How it reaches another version |
|---|---|---|
| **Pure** | Contract models and a mod's kernels: plain Java, no Minecraft | Written once in modern Java. It holds on every era by law (below) and runs there as downgraded bytecode. It reaches every version for free. |
| **Platform** | The code that touches Minecraft and a loader | Ported where the game changed: Stonecutter across neighbouring versions, a provider per loader. Native, with the platform's full power. |

The architecture has one goal: **make the platform layer small and the pure layer large**, and prove the pure layer
on every era automatically, so a new version target costs only its platform port.

## The table

Three files hold what the plugin knows about versions. The repository check (`myceliumRepository`) proves they agree.

| File | What it holds |
|---|---|
| `eras/minecraft.json` | Every version, by era: its Java, the loaders a mod can target, how its code is named, and the walls a port crosses. Ordered newest first. |
| `eras/loaders.json` | Each loader's Java packages, and the game's own, each with where it was read. The pure layer may mention none of them (P1). |
| `eras/versions.json` | Every real version, from Mojang's launcher manifest: releases, betas and alphas (snapshots are not targets). |

**Bounds.** An era covers every version from `from` to `to`, both included. A bound is either a version as the
manifest names it (`1.20.5`, `b1.0`, `c0.30_01c`), or a whole line, `<line>.x` (`26.x`, `1.21.x`): every version of
that line, those still to come included. A line is how an era says "and whatever ships next" without naming a
version that does not exist. Order is Classic, then alpha, then beta, then releases. A pre-release belongs to the
era of the release it precedes. Versions before Classic (`rd-`, `in-`, `inf-`) are not targets.

**What the repository check proves.** Every named bound is a real version or a line. Every era runs forwards. Every
real version falls in exactly one era. Every loader an era names has its packages in `eras/loaders.json`.

**Keeping it true.**
- When Mojang ships a version, run `./gradlew myceliumRefreshVersions`. Builds stay offline; this is the one task that
  reads the network. If the new version opens a new line, the check says which era it falls outside.
- When a loader appears, write its packages into `eras/loaders.json` with their source. From then on law P1 guards
  them everywhere, with no list kept anywhere else.
- A row's facts (loaders, Java) are re-verified against the loaders' own mavens when a consumer first ports to it.

## Targets drive everything

A consumer names its target, and everything era-shaped follows from the table:

```kotlin
mycelium {
    minecraft = sc.current.version        // this project's era: its gate, and a record in the evidence manifest
}
val requiredJava = JavaVersion.toVersion(mycelium.javaFor(sc.current.version))   // the toolchain, from the same table
```

- **The era gate.** With no `eras(...)` declared, a project runs its own era's gate (`myceliumEraJava21` on 1.21.1,
  `myceliumEraJava25` on 26.x). Adding a 1.12.2 target adds a Java 8 gate. Nothing else changes.
- **Readiness.** `allEras()` runs every Java some era has used. Mycelium runs it on itself. A mod that runs it on its
  main project keeps its pure layer ready for a version nobody has ported yet.
- **Evidence.** `META-INF/mycelium/evidence.json` records the target's era (Minecraft, Java, loaders, names) and every
  era gate's result, including which bundles it expected, checked and skipped.

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

Its laws are checked on every build. Each was proven by a planted violation that failed the build (`CHANGELOG.md`).

| Law | Statement | Checked by |
|---|---|---|
| **P1 pure** | It mentions no Minecraft, loader, Mixin or integration package (every root in `eras/loaders.json`), and none of the artifact's own platform packages (every other main source package). No package is split between the layers. | `myceliumPurity`, on the source (comments and strings ignored) |
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
- a contract with laws and a rejection matrix (`java.util.Random`, modulo bias, shared state, lenient bounds, a
  source that draws nothing);
- SplitMix64, bit-identical to `SplittableRandom` (T1's golden checksum), so no seed recorded before changed;
- Mycelium's harness `Seeded` wraps it (`harness/sources.txt`), and `SeededIdentity` in `harness/test` checks the
  identity live on every run;
- a mod imports it under its own namespace, for its kernels and its game code alike.

## How an era gate runs (`myceliumEraJava<N>`)

The unit is a **bundle**: `classes/` plus `bundle.properties` (a name, the release it was compiled for, its suite
mains). Each contract `myceliumVerify` builds is one; the pure layer is one. For each bundle:
1. **Bytecode.** As compiled if the era is at least its release. Otherwise JvmDowngrader downgrades it to the era's
   class version and shades in its Java API backports under the artifact's own prefix (`shadePrefix`, default from
   the namespace), so two mods' backports never collide.
2. **Stubs**, then **linkage**, then **behaviour**, as above. The report is `build/mycelium/eras/java<N>/era.json`: the
   bundles expected, checked and skipped. A gate that checked nothing says so.

The era's JVM is a Gradle toolchain, provisioned when absent (foojay). JvmDowngrader resolves only when some gate is
older than the bytecode.

**Known tool issue:** JvmDowngrader 2.0.1 races in its shading (a HashMap shared across threads), roughly one run in
four. The gate retries once and logs the retry: one failure is noise, two are real.

## The platform layer: the map

What a mod must change between versions. The same table drives the gates (`eras/minecraft.json`).

| Minecraft | Java | Loaders | Names in the code | What changes for a mod |
|---|---|---|---|---|
| 26.x | 25 | Fabric, NeoForge, Forge | unobfuscated | The game ships unobfuscated: mappings are gone. Forge from 26.1.2, on EventBus 7. |
| 1.20.5 – 1.21.x | 21 | Fabric, NeoForge, Forge | Mojang | **Data components replace item NBT** (1.20.5); holder and item-model reworks (1.21.2, 1.21.4). |
| 1.20.2 – 1.20.4 | 17 | Fabric, NeoForge, Forge | Mojang | The configuration network phase; NeoForge splits from Forge. |
| 1.18 – 1.20.1 | 17 | Fabric, Forge, Quilt | Mojang | World height −64..320 (1.18). 1.20.1 is the largest modded population of its time. |
| 1.17.x | 16 | Fabric, Forge | Mojang | Java 16. |
| 1.14 – 1.16.5 | 8 | Fabric, Forge | Mojang from 1.14.4 | Fabric exists. 1.16 adds data-driven dimensions. |
| 1.13 | 8 | Forge, Legacy Fabric, Ornithe | MCP | **The Flattening**: numeric ids become named blocks and states; data packs begin. |
| 1.8 – 1.12.2 | 8 | Forge, Legacy Fabric, Ornithe | MCP | The great modpack era. Block states (1.8). No data packs: content comes from code. |
| 1.0 – 1.7.10 | 8* | Forge, Legacy Fabric, Ornithe | MCP | Numeric ids and metadata. Mycelium the block has existed since Beta 1.9 / 1.0. |
| Beta, Alpha, Classic | 8* | Babric (Beta 1.7.3), Ornithe | Feather | No registries, no events: everything is hand-rolled. |

\* These shipped for Java 6, but they run on Java 8 today, as does every modern loader for them.

## Lessons from running whole suites

Compiling for a version proves little. Running a mod's whole gametest suite there finds what compiling never does.
These were learned porting a consumer across 26.x and 1.21.x on three loaders (`CHANGELOG.md` has the dates):
- **A copy that is not a copy.** 1.21.1's `PalettedContainer.copy()` keeps the source's palette and resize handler
  (`SingleValuePalette.copy()` returns itself). A write into the "copy" resizes the original, and the copy's own write
  fails, or crashes the server tick later ("value N is not in the specified inclusive range"). 26.x's is a real copy.
  Copy sections through one function that rebuilds them cell by cell where the platform's copy is shallow.
- **Lambdas have different names in different dev jars.** A mixin that targets a lambda by name
  (`lambda$create$1`) finds it where the runtime is Mojang-named (26.x; NeoForge 1.21.1), but not in Fabric 1.21.1's
  dev jar, where lambdas keep intermediary names (`method_40377`). With `require = 0` the miss is silent. List both
  names, and check with `javap -p`.
  `Position` the first time 1.21.1 ran a trip. A validating model is worth more on the older target, where fewer people
  look.
- **A new loader is a new test order.** Tests that run many at a time on a packed grid share world state. A test of
  shared state passed on two loaders by luck and failed on the third's order, every run. A test of shared state runs off
  the grid.
- **An event that says "loaded" can mean "seen".** Fabric's entity-load hook fires whenever an entity's tracking
  starts, including while the level walks a chunk's entities as the chunk comes into view. Removing an entity there
  breaks the walk and crashes the server. NeoForge and Forge ask before the entity is placed. Work that adds or removes
  entities waits for the tick's end.

The rule that follows: a rung is not ported until its whole suite runs there, not only its build.

## Not yet built (in order)

1. **Shipping downgraded jars.** A target whose Java is older than the source's release needs its jar downgraded and
   shaded at build time. The era gate already proves such a jar would hold; the first rung that needs it builds it.
2. **Portability surface.** A report of how much of an artifact touches Minecraft outside its ports, per package. It
   makes the cost of the next rung visible before the port starts, and the aim is to shrink it. Stonecutter bridges
   neighbouring versions; a provider directory per era crosses the walls.
3. **Rungs**, one at a time, each when a consumer needs it:
   - 1.20.1 (Fabric, Forge);
   - 1.18.2;
   - 1.16.5 (Java 8);
   - 1.12.2 (Forge, Legacy Fabric; no data packs);
   - 1.7.10;
   - Beta 1.7.3.
