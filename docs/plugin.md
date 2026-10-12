# The plugin

`dev.woflo.mycelium` is what a mod applies. It is the build-time half of the machine (`docs/architecture.md`): it
imports the contract models a mod selects, runs the gates, proves the pure layer on every era's Java, ships the jar,
and writes the evidence. It knows no loader: version and loader knowledge is data in `eras/` (`docs/eras.md`).

This page is the consumer's reference. The plugin's own classes are mapped in `AGENTS.md`.

## Using it in a mod

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../mycelium/gradle-plugin")   // wherever your checkout is
}
```

`build.gradle.kts` (or each loader's buildscript):

```kotlin
plugins {
    id("dev.woflo.mycelium")
}


Imported models become the mod's own private code under `namespace`. Two mods that pin different majors carry
different code and cannot conflict. No shared runtime is introduced.

### Optional blocks

- `pure { }`: the mod's pure layer, proven on every era and shipped downgraded where an era needs it (`docs/eras.md`).
- `integration(...)`: an optional integration kept isolated (`docs/integrations.md`).
- `ports(...)`: the mod's own packages that are meant to touch the platform, for `myceliumSurface`.
- `range { }`: the Minecraft versions the artifact runs on and what proves it, read from a ledger or written out.
  `myceliumRange` holds the claim to the era table, the proofs and the loader's own metadata
  (`docs/decisions/0009-the-range-gate.md`).
- `range { linkage { } }`: adds `myceliumLinkage`, which holds the finished artifact's every reference to the game's
  published names on each version of the range that has name data
  (`docs/decisions/0010-linkage-at-each-version.md`). A loader build can also supply the exact dedicated-server game
  archive for its target version, already remapped to the artifact's runtime namespace; this catches members present in
  merged development mappings but absent from the server jar. A consumer opts in with `serverRuntimeArchive.set(...)`,
  `serverRuntimeNamespace.set("intermediary")` (or its actual runtime namespace) and
  `serverRuntimeOwns.set(listOf("net/minecraft/"))`.
- `privateLibrary("library.api", "my.mod.privateapi")`: carries a feature library privately (below).
- `artifactJars("jar")`: selects the Jar tasks that receive the artifact's private pure classes and evidence, which
  keeps independent probes and source archives separate. An empty selection attaches to every Jar task; a nonempty
  selection must name distinct Jar tasks in that project. The project's contract, purity and era checks apply
  whichever archives receive the artifact.

## Tasks

| Task | Effect |
|---|---|
| `myceliumImport` | Copies each selected contract's `model` (and its requirements') into `build/generated/sources/mycelium/java` under the mod's namespace, and adds it to `main`. A model that reaches the harness is refused. |
| `myceliumVerify` | Compiles and runs each selected harness; collects each harness's report. In `check`. `-Pmycelium.contracts=a@1,b@1` overrides the selection for one run. |
| `myceliumUsage` | Reports imported contracts the mod's own sources never use. Warns; `failOnUnused = true` fails. In `check`. |
| `myceliumIsolation` | An optional integration's API is mentioned only in its home package and its gates. Before `compileJava` and in `check`. |
| `myceliumPurity` | The pure layer mentions no platform package (P1), and reads no clock, environment or unseeded chance (P3). |
| `myceliumPureCompile` | Compiles the pure layer alone. If it compiles there, it is pure in fact. |
| `myceliumEraJava<N>` | One era's gate: every bundle on that Java's JVM (stubs, linkage P2, behaviour). `myceliumEras` runs every declared era. |
| `myceliumPureShip` | Puts the pure layer's classes into this target's jar, downgraded and shaded from the era gate's own output when the era needs it, a no-op when it does not (below). |
| `myceliumDowngradeJar` | Downgrades the jar's own classes to the era's Java, optionally isolating declared feature libraries first, and holds the result to that Java. Runs only when a consumer's loader step takes its output (below). |
| `myceliumCapsule` | When `mycelium.capsule` is configured, checks outer and nested class-file ceilings, source-to-runtime class mappings, and erased class and member references from bytecode and `StackMapTable` frames against ordered, declared runtime artifacts. Generic `Signature` attributes and source-debug local-variable tables are not checked; the gate does not replace JVM verification. Writes exact SHA-256 inputs to `build/mycelium/capsule/capsule.json`. In `check`. |
| `myceliumSurface` | Reports platform references per package of the mod's own sources, split into declared `ports(...)` and everything else (below). |
| `myceliumRange` | When `mycelium.range` is declared, holds the claimed Minecraft versions to the era table (the loader exists on each), the proofs (a keystone, and a proof on each side of every wall inside the range) and the metadata the built artifact declares to its loader (it accepts every claimed version). Records each version as `proven`, `pending` or `interpolated` in `build/mycelium/range/range.json`, which the evidence manifest carries. In `check`. |
| `myceliumLinkage` | When `mycelium.range { linkage { } }` is declared, holds every class, method and field the finished artifact references to the game's published names (`eras/names.json`: Searge, intermediary, Mojang's mappings, the game's own jar, an API's jar, each pinned by SHA-256 and cached) on each version of the claimed range that has name data. The build fails naming a reference and version when a member is absent. With the optional server inputs it also holds the target version to an exact dedicated-server game inventory, and records that archive's hash. In `check`. |
| `myceliumEvidence` | Writes `META-INF/mycelium/evidence.json` into the selected artifact jars (every Jar by default): target, era, each contract's model hash, what ran and what was not checked, with why. Design-time evidence only. |
| `myceliumRepository` | This repository only (`dev.woflo.mycelium.repository`): the repository against everything it states. |
| `myceliumRefreshVersions` | This repository only: rewrites `eras/versions.json` from Mojang's launcher manifest, by hand. |
| `myceliumRefreshNames` | This repository only: pins name data in `eras/names.json` for `--namespace=<name> --versions=<v,...>`, or reports pins whose files changed, by hand. |

## Proven by fixtures

Each fixture is a small consumer build that must pass, with planted breaks the gate must refuse by name.

- `./gradlew -p gradle-plugin/src/test/fixtures/private-consumer check` proves compilation with an incompatible
  platform aggregate on the classpath and executes the isolated artifact on Java 8. The fixture references ASM 9 APIs
  absent from its compile-only ASM 4 aggregate.
- `./gradlew -p gradle-plugin/src/test/fixtures/range-consumer check` proves a Fabric artifact's metadata written from
  its range and its evidence manifest carrying the claim. `-PrangeBreak=wall` and `-PrangeBreak=metadata` each plant a
  break `myceliumRange` must refuse by name; `-PsharedOutput` puts classes and resources in one directory, as
  ForgeGradle does, which the task must order itself after `compileJava` to read.
- `./gradlew -p gradle-plugin/src/test/fixtures/linkage-consumer check` proves a Fabric artifact whose range's first
  version is linked from name data the build pins itself and whose second stays proven. `-PlinkageBreak=1.21` removes the
  method the artifact calls from 1.21's data, which `myceliumLinkage` must refuse by naming the reference and the
  version. The fixture supplies an exact server game archive; `-PlinkageServerBreak=1.21.1` leaves the method in Tiny
  mappings but removes it from that server archive, and the same gate must refuse it. `-PlinkageServerRuntime=false`
  proves the optional inputs may remain unset.
- `gradle-plugin/src/test/fixtures/ship-consumer` ships a pure layer written for Java 21 to a Java 8 era
  (`docs/decisions/0006-shipping-downgraded-jars.md`).

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

### Private feature libraries

`privateLibrary("library.api", "my.mod.privateapi")` gives a feature library an artifact-owned package identity.
Its dependencies are declared in the `myceliumPrivateLibraries` configuration, which also supplies the compile
classpath. `myceliumDowngradeJar` merges those jars, rewrites the artifact's callers and the libraries' bytecode,
reflection class names and service descriptors, then downgrades that result. Every library class must belong to a
declared package; overlapping translations and conflicting jar entries fail. Library license resources are retained
under `META-INF/private-libraries/<jar>/`. The downgrade report records input SHA-256 hashes, output identity and
relocated class counts; these are build facts, not game evidence.

Java 8 artifacts omit multi-release variants that their JVM ignores. Newer artifacts retain and relocate them.
Explicit artifact module descriptors are refused: merging a named dependency into a named consumer needs its own
module graph change. No private-library declaration means no relocation or merge, and existing artifacts retain
that behavior. A consumer's loader step must take the task's output even when its source Java needs no downgrade.

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
a provider directory per era still crosses the walls the era table (`docs/eras.md`) names.

