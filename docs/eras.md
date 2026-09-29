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

## Shipping downgraded jars (`myceliumPureShip`)

A target whose era is older than the pure layer's release needs its jar to carry the downgraded, shaded bytecode,
not the classes as compiled. `myceliumPureShip` does that: it depends on this target's own `myceliumEraJava<N>`
(added automatically if a consumer's `eras(...)` does not already run it) and copies, out of that gate's own jar,
exactly two kinds of class — the artifact's declared pure packages (`pure.sources`, plus every imported contract
model, which join the pure layer) and anything JvmDowngrader shaded under the artifact's `shadePrefix` (its own
backport support classes, which the downgraded bytecode calls directly). The shared Mycelium harness and the
artifact's test suites are part of the same bundle and never ship (`fixtures/` and `harness/` never ship). Nothing
is recompiled or re-downgraded for shipping: the bytes are exactly the ones the era gate already proved hold
(stubs, linkage, behaviour), which is the only honest way to say "the era gate checked exactly these bytes"
(`docs/decisions/0006-shipping-downgraded-jars.md`).

A target whose era is at least the pure layer's release needs no downgrade: `myceliumPureShip` is a no-op and says
so (`ship.json`'s `why`). Every `Jar` task adds the ship task's classes and, only when a downgrade actually
happened, excludes the same packages from wherever `main` already compiled them (a consumer may add its pure
sources to `main` directly, as `docs/decisions/0001-substrate.md` does for Somnium's kernels) — one path, never two, and never
touched when there is nothing to ship.

`myceliumPureShip` works on the standard `jar` task; a loader's remap or repackaging step (Loom's `remapJar`,
ForgeGradle/jarJar) consumes that task's output exactly as it already does, and needs to know nothing about
Mycelium.

## Downgrading the jar's own classes (`myceliumDowngradeJar`)

`myceliumPureShip` carries the pure layer to an old era from the era gate's own bytes. A port written in the same modern
Java as the pure layer (records, `List.of`, pattern matching) has the same problem for the rest of the jar, and no
gate bundle to take the bytes from. `myceliumDowngradeJar` takes the jar itself, the standard `jar` task's output by
default, and the classpath it was compiled against, and runs JvmDowngrader over all of it: bytecode to the era's class
version, the Java APIs it calls backported into classes shaded under the artifact's `shadePrefix`. Then it holds the
result to the same two things as the gate: no call of the artifact's own may be one the downgrader had no backport
for, and every `java.*` and `javax.*` class, method and field the result reaches must exist on a real JVM of the
era's version (law P2, over the whole jar). A jar whose classes already run on the era is copied through and the
report says so.

The classes `myceliumPureShip` already put under the shade prefix are set aside while the rest is downgraded and put back
where the shade did not write them again, so a backport is never in the jar twice. A nested or synthetic class the
artifact compiled itself (`Outer$1`) makes way for the pure layer's shipped `Outer`, whose other compilation has no such
class.

Nothing in `check` or in any jar runs it. A consumer puts its output where the loader's tooling takes the jar (Loom's
`remapJar` input, say), before any remapping, so the classpath the classes were compiled against is the one the
downgrader reads. `build/mycelium/downgrade/report/downgrade.json` records what ran; the design-time claim stays
what `DESIGN.md` §11 allows: the bytes were downgraded and their Java references resolve on the era's Java, not that the
game runs them.

## The portability surface (`myceliumSurface`)

Per package of the artifact's own main sources (never the imported models, proven pure already), `myceliumSurface`
counts references to a platform package (every root `eras/loaders.json` names, the game's own included), the same
textual match `myceliumPurity` and `myceliumIsolation` already use. A package the consumer names in
`mycelium { ports("...") }` is expected to touch the platform; every other package's references are the surface —
the cost the next rung would have to eat, made visible before the port starts, with the aim of only ever shrinking
it. `build/mycelium/surface/surface.json` holds the full report; `failOnGrowth` compares it against a checked-in
baseline of the same shape, opt-in and off by default.

It is a textual count (the same reference used three times on one line counts three), not a distinct-type or
call-graph count, and it does not itself cross a wall: Stonecutter bridges neighbouring versions within a port, and
a provider directory per era still crosses the walls this table describes.

## Not yet built (in order)

1. **Rungs**, one at a time, each when a consumer needs it:
   - 1.20.1 (Fabric, Forge);
   - 1.18.2;
   - 1.16.5 (Java 8);
   - 1.12.2 (Forge, Legacy Fabric; no data packs);
   - 1.7.10;
   - Beta 1.7.3.
