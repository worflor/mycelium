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

mycelium {
    root = rootProject.file("../mycelium")
    namespace = "com.example.mymod.mycelium"      // artifact-owned prefix (DESIGN §12)
    minecraft = "1.21.1"                          // the era: its Java, its gate, its evidence (docs/eras.md)
}
```

That is a whole declaration. The rest is derived, and a declaration overrides what is derived (below). Imported models
become the mod's own private code under `namespace`. Two mods that pin different majors carry different code and cannot
conflict. No shared runtime is introduced.

The plugin offers; it does not police (`docs/decisions/0017-offer-dont-police.md`). Every check has a level you can set,
nothing a text check says keeps your code from compiling, and what the build can find out for itself it does not ask you
to write down.

### What is derived

| Fact | Derived from | Override |
|---|---|---|
| An integration's gates | Every source outside the integration's home package that references the home is a gate, reported in `isolation.json` (`gates: derived`). I1, the law that the other mod's API stays in the home, is still checked in full. | The fourth argument of `integration(...)`, a closed list of gate paths: I2 then reports a reference from anywhere else. |
| The jar that ships | The project's `jar` task: the code and evidence go in it, and a loader's remap or repackaging step takes it from there. | `artifactJars("jar", "otherJar")`, or `artifactJars("*")` for every Jar task. |
| The pack format, mixin level and Minecraft range | `eras/`, for the target version (below). | write your own. |
| Range proofs | Not derived: the plugin cannot see which versions your tests ran on. A range with no keystone warns (R1), each version stays `interpolated`, and the manifest says so. | `range { keystones(...) }`. |
| Contract harnesses | Mycelium's own check already ran each one and recorded a fingerprint of the sources (`contracts/proofs.json`). `myceliumVerify` takes a contract whose sources are unchanged, on every Java your gates run, as proven; the manifest records `proven-upstream` and the fingerprint. | `-Pmycelium.verify=always` runs every harness here. A changed source always runs. |

The list is chosen by `myceliumDeriveContracts`, a task, so it reads the sources as the compile will: after whatever writes
them. A value computed while the build is configured would see them before any generator ran (a clean Stonecutter build has
no generated sources yet, and the configuration cache stores such a value before any task runs), and the import would miss
every contract only a generated source names. The task scans the main source set's directories as they are when it runs,
Stonecutter's generated directory for a version that is not the active one included, and writes `build/mycelium/contracts.txt`;
the import, verify, usage and evidence tasks and the era gates read the list from there. It waits for the tasks that produce
the source directories, and for Stonecutter's `stonecutterGenerate` by name, which nothing else declares as the sources' producer.
A text scan of the code (comments removed) is enough because a contract's package is under the mod's own namespace: no other
text can name it. An explicit `contracts(...)` passes through the task unchanged.

### Optional blocks

- `checks { }`: the level of each check and per-site allowances (below).
- `packMeta { }`, `overlays { }`, `mixinJava`, `minecraftRange(loader)`: what the era table generates for the target (below).
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
  keeps independent probes and source archives separate. The default is the `jar` task, the jar that ships;
  `artifactJars("*")` attaches to every Jar task; a selection must name distinct Jar tasks in that project. The
  project's contract, purity and era checks apply whichever archives receive the artifact.

## Tasks

| Task | Effect |
|---|---|
| `myceliumImport` | Copies each selected contract's `model` (and its requirements') into `build/generated/sources/mycelium/java` under the mod's namespace, and adds it to `main`. A model that reaches the harness is refused. |
| `myceliumBridgeImport` | Copies the bridges the imported contracts bring (a contract's registry row lists them) into `build/generated/sources/mycelium/bridges/java`, as the realization table's variant and overlays for the target Minecraft version, rewritten under the mod's namespace, and adds them to `main`. With `bridges { conformance() }` the bridge's conformance cases go to `build/generated/sources/mycelium/conformance/java`, added to the `gametest` source set (else `main`; `conformance("name")` picks one). A bridge with no realization on the target is not imported (B2). Skipped in a verification project. |
| `myceliumBridges` | Checks the mod's bridge choices (B1, B2) and writes `build/mycelium/bridges/bridges.json`, which the evidence manifest records. In `check`. |
| `myceliumBridgeLinkage` | This repository only: every bridge variant, with each overlay, compiled against the game and linked against every version it covers that has name data (`eras/names.json`); versions without data are reported, not failed. In `check`; it needs a JDK as new as the newest era's Java (a toolchain). |
| `myceliumVerify` | Compiles and runs each selected harness whose sources are not the ones Mycelium's own check proved (`contracts/proofs.json`); collects each harness's report. In `check`. `-Pmycelium.contracts=a@1,b@1` overrides the selection for one run; `-Pmycelium.verify=always` runs every harness. |
| `myceliumUsage` | Reports imported contracts the mod's own sources never use (U1). Warns; `failOnUnused = true` fails. In `check`. |
| `myceliumIsolation` | An optional integration's API is mentioned only in its home package (I1), and its home only from its gates (I2). In `check`; never before `compileJava`. |
| `myceliumPurity` | The pure layer mentions no platform package (P1, warns), shares no package with the platform (P1.split, fails), and reads no clock, environment or unseeded chance (P3, warns). In `check`; never before `compileJava`. |
| `myceliumPackMeta` | Writes the target's `pack.mcmeta` from the era table's `packFormat` rows, in the schema the version reads. Joins the resources when `packMeta { }` is declared. |
| `myceliumPureCompile` | Compiles the pure layer alone. If it compiles there, it is pure in fact. |
| `myceliumEraJava<N>` | One era's gate: every bundle on that Java's JVM (stubs, linkage P2, behaviour). `myceliumEras` runs every declared era. |
| `myceliumPureShip` | Puts the pure layer's classes into this target's jar, downgraded and shaded from the era gate's own output when the era needs it, a no-op when it does not (below). |
| `myceliumDowngradeJar` | Downgrades the jar's own classes to the era's Java, optionally isolating declared feature libraries first, and holds the result to that Java. Runs only when a consumer's loader step takes its output (below). |
| `myceliumCapsule` | When `mycelium.capsule` is configured, checks outer and nested class-file ceilings, source-to-runtime class mappings, and erased class and member references from bytecode and `StackMapTable` frames against ordered, declared runtime artifacts. Generic `Signature` attributes and source-debug local-variable tables are not checked; the gate does not replace JVM verification. Writes exact SHA-256 inputs to `build/mycelium/capsule/capsule.json`. In `check`. |
| `myceliumSurface` | Reports platform references per package of the mod's own sources, split into declared `ports(...)` and everything else (below). |
| `myceliumRange` | When `mycelium.range` is declared, holds the claimed Minecraft versions to the era table (the loader exists on each), the proofs (a keystone, and a proof on each side of every wall inside the range) and the metadata the built artifact declares to its loader (it accepts every claimed version). Records each version as `proven`, `pending` or `interpolated` in `build/mycelium/range/range.json`, which the evidence manifest carries. In `check`. |
| `myceliumLinkage` | When `mycelium.range { linkage { } }` is declared, holds every class, method and field the finished artifact references to the game's published names (`eras/names.json`: Searge, intermediary, Mojang's mappings, the game's own jar, an API's jar, each pinned by SHA-256 and cached) on each version of the claimed range that has name data. The build fails naming a reference and version when a member is absent. With the optional server inputs it also holds the target version to an exact dedicated-server game inventory, and records that archive's hash. In `check`. |
| `myceliumPresentation` | Compiles the mod's item presentation programs (`item.presentation@1`) for the target Minecraft version: the era table chooses an item definition or an item model with overrides, and the task writes it, with the plan the runtime adapter reads, into generated resources. A law the programs break, or a selection the committed lock lacks, fails the build with the diagnostic and the lines to add. Runs before `processResources`; skipped when there is no presentation source directory. |
| `myceliumPresentationLock` | Appends the legacy codes the presentation programs need to the committed lock in the mod's sources, on request; never part of a build, and never changes an existing line. |
| `myceliumEvidence` | Writes `META-INF/mycelium/evidence.json` into the selected artifact jars (every Jar by default): target, era, each contract's model hash, what ran and what was not checked, with why. Design-time evidence only. |
| `myceliumRepository` | This repository only (`dev.woflo.mycelium.repository`): the repository against everything it states. |
| `myceliumRecordProofs` | This repository only: after every harness and every era gate has passed, writes `contracts/proofs.json` (a fingerprint of each contract's sources and the Javas the gates ran on) so a mod need not run them again. In `check`. |
| `myceliumRefreshVersions` | This repository only: rewrites `eras/versions.json` from Mojang's launcher manifest, by hand. |
| `myceliumRefreshNames` | This repository only: pins name data in `eras/names.json` for `--namespace=<name> --versions=<v,...>`, or reports pins whose files changed, by hand. |

## Checks

Every check has a level: `off`, `warn` or `fail`. The defaults fail only for **silent breakage**, a build that would ship
something that does not work and say nothing. Everything else warns. A check that is a proof (the pure layer compiles alone
`myceliumPureCompile`; a contract's harness; an era's stubs and linkage `myceliumEraJava<N>`; a capsule's ceilings) is not
in the table: it holds or the artifact is not what it says. The table is the checks that explain.

| Check | Default | What it holds | What it prevents |
|---|---|---|---|
| `P1` | warn | the pure layer names no platform package (text) | a pure layer that is not pure; `myceliumPureCompile` is the proof, this says where |
| `P1.split` | **fail** | no package is split between the pure layer and the platform sources | two layers in one package: a pure class shadowed by, or package-private-linked to, the platform |
| `P3` | warn | the pure layer reads no clock, environment or unseeded chance (`SecureRandom`, `UUID.randomUUID`, `getenv`, `getProperty`, `Math.random`, `new Random()`) | a pure layer that differs run to run. A policy: a mod that declares a law this breaks sets `fail` |
| `I1` | **fail** | an optional integration's API is mentioned only in its home package | `NoClassDefFoundError` when the other mod is not installed |
| `I2` | warn | the integration's home is referenced only from its gates (derived unless declared) | the home loaded without its guard |
| `B1` | fail | a bridge the mod overrides (`bridges { override(slug, className) }`) has a class that exists and implements the bridge's port | an override the contract's laws cannot be held to |
| `B2` | warn | an imported bridge has a realization on the target version | a bridge the build silently left out, so its port has no native half there |
| `U1` | warn | an explicitly imported contract is referenced by the mod's own sources (`failOnUnused = true` sets `fail`) | a manifest that says the mod carries a contract it never uses |
| `S1` | warn | the portability surface has not grown past `surfaceBaseline` (`surfaceFailOnGrowth = true` sets `fail`; `off` skips `myceliumSurface`) | the next port growing unseen |
| `R0` | **fail** | the claimed range is real: both ends are versions, the loader exists on each, proofs name real versions the artifact claims | a claim about versions that do not exist |
| `R1` | warn | the range declares a keystone | a range with no version it was run on (every version stays `interpolated`) |
| `R2` | warn | each side of a wall inside the range has a proof | a claim across a wall nobody crossed (those versions stay `interpolated`) |
| `R3` | **fail** | the loader's metadata accepts every claimed version | the loader refusing the artifact where it claims to run |
| `L1` | **fail** | every game reference resolves on each claimed version that has name data | `NoSuchMethodError` on a claimed version |
| `L2` | warn | pinned name data was available (not offline: when the data could not be had or read, the version stays as it was) | a version quietly not linked |
| `PR1` | **fail** | the presentation programs compile for the target and the committed lock holds every selection | an item drawn with no model (a `warn` writes no presentation files and says so) |

Set a level in one place, and allow one site with a reason:

```kotlin
mycelium {
    checks {
        fail("P3")                   // this mod declares a law the clock breaks
        off("S1")
        level("I2", "warn")          // the same, by name
        allow("P3", "kernel/Rng.java", "seeded by the caller")   // a source path (matched as a path suffix), a version, or "*"
    }
}
```

A misspelt check id, a level other than `off`, `warn` or `fail`, and an allowance without a reason are refused where they are
written. The levels, with each default they replaced, and every allowance with its reason are in the evidence manifest
(`checks`), beside what the text checks last found (a finding an allowance covered is shown as `allowed`, with its reason):
nothing is hidden by being allowed. A text check runs in `check` and never before `compileJava`, so a mod in the middle of a
refactor can always compile.

## Bridges

A bridge is the native half of a divide: source that touches Minecraft, kept in `bridges/<slug>/` here and imported as source
under the mod's namespace (`BRIDGE-PACKAGE.md`, `docs/decisions/0016-bridges.md`). Nothing runs at runtime; the mod gets the
source for its target.

- **Selection.** A mod gets a bridge when its sources reference `<namespace>.bridges.<slug>` (or `bridges { use("slug") }`),
  which also brings the bridge's contract. The realization table (`eras/minecraft.json`, one table per divide) says which
  variant covers the target version; the variant's overlays for that version replace or add same-named files. A `none` row,
  or no row, means the bridge is not imported and B2 says so.
- **Override.** `bridges { override("item-carrier", "com.me.MyCarrier") }` imports nothing for that bridge and checks only that
  the class implements the port (B1). `skip("slug")` leaves a bridge out. Overrides are always possible; the evidence records
  them.
- **Conformance.** `bridges { conformance() }` also imports the bridge's conformance cases, plain Java the mod registers as
  game tests (`ItemCarrierConformance.cases(...)` for `item-carrier`, `ClockConformance.cases()` and `.timed(...)` for `world-clock`, `PresenterConformance.cases(...)` for `item-presentation`).
  They run on the target's real game objects, which is what the laws about stacks, packets, saves and clocks need. A variant's
  `conformance-resources/` (a clock is a datapack file on 26.1 and later, so its cases need one) goes to `build/generated/resources/mycelium/conformance`,
  added to the same source set's resources. The jar a mod ships leaves those data files out (`shipConformanceResources()` keeps
  them) and, with `conformanceClassesOutOfJar()`, the conformance classes; the `conformance-consumer` fixture proves the four modes.
- **Evidence.** The manifest's `bridges` section records the variant and overlays chosen for the target, or the override.

## Declaring once (`myceliumDefaults`)

A multi-project build (Stonecutter: one project per version and loader) writes the mod's block once on the root project and
every project inherits it:

```kotlin
// root build script
plugins { id("dev.woflo.mycelium.defaults") }

myceliumDefaults {
    root = rootProject.file("../mycelium")
    namespace = "com.example.mymod.mycelium"
    artifactId = "mymod"
    integration("voicechat", "com.example.voice", "de.maxhenkel.voicechat")     // no gates to list: they are derived
    pure { sources.from(rootProject.file("kernels/src")); release = 21 }
    checks { allow("P3", "kernel/Rng.java", "seeded by the caller") }
}

// each loader/version project
mycelium { minecraft = sc.current.version }
```

`myceliumDefaults` takes everything `mycelium` does. A project's own scalar setting wins; what only a project knows (its
name, its Minecraft version) stays in the project; the pure layer's sources and tests add up; the check levels and
allowances merge, the project's after the default's. A list the project writes itself (`contracts(...)`, `ports(...)`,
`integration(...)`) replaces the default list. The plugin's own tasks read the merged result, so the evidence manifest is
the same whether a setting came from the root or the project.

## Generated from the era table

`eras/minecraft.json` knows what changes at which version, and so the mod does not. For the target `minecraft`:

- **`pack.mcmeta`.** `mycelium { packMeta { description = "My mod resources" } }` has `myceliumPackMeta` write it into the
  resources, in the schema the version reads: before 1.20.2 a `pack_format`; 1.20.2 to 1.21.8 a `pack_format` and
  `supported_formats` [resource, data]; from 1.21.9 `min_format` and `max_format` (resource and data majors; a major covers its
  minors) and no `pack_format`. The `packFormat` rows name the game's own `version.json` `pack_version`; each row's `source` says
  whether it was read from a game jar or from minecraft.wiki's "Pack format" table, and `myceliumRepository` proves every real
  version from 1.6.1 (the first with resource packs) falls in exactly one row. Forge serves a mod's data only with a
  `pack.mcmeta`; Fabric and NeoForge make their own, so declare it in the Forge buildscript.
- **The mixin compatibility level.** `mycelium.mixinJava` is `JAVA_8`, `JAVA_17`, `JAVA_21` or `JAVA_25` for the target's Java;
  `mycelium.mixinJava(21)` caps it for a Mixin that stops lower (Forge's 0.8.7). Use it as the value of
  `compatibilityLevel` when expanding a mixin config.
- **The mod metadata's Minecraft range.** `mycelium.minecraftRange("fabric")` is a provider of the range as that loader's
  metadata writes it (`CompatExpr`: a Fabric `depends`, a Forge `versionRange`), from the declared `range` if there is one, else
  from the oldest to the newest `minecraft` of the projects in the build. It names a contiguous range, not the versions
  between that were never targeted: declare `range { keystones(...) }` to say which were run.
- **Resource overlays per schema era.** `overlays { base(file("src/main/resources-1211")); from("26.1", file("src/main/resources-2601")) }`
  adds to the resources the directory whose version is the newest at or below the target. It chooses by version bound, not by
  data; overlays inside one jar (`overlays` in `pack.mcmeta`) are not generated yet (`docs/STATUS.md`).

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
- `./gradlew -p gradle-plugin/src/test/fixtures/flexible-consumer check` proves a mod whose whole declaration is a namespace and a
  target: its contract found from its sources, an integration's gates derived, the pack metadata written for 1.21.1, an
  overlay chosen, only `jar` carrying the evidence, and the checks at their levels in the manifest. `-Pflex=failP3`,
  `-Pflex=allowP3` and `-Pflex=offP3` set the pure layer's clock check to each level; `-Pflex=leak` plants an integration
  leak (I1) and `-Pflex=pureLeak` a pure layer that does not compile alone: `compileJava` still passes, `check` does not.
- `./gradlew -p gradle-plugin/src/test/fixtures/derive-consumer check` proves the derivation in a clean build with the configuration cache on: a fully qualified reference with no import, the pure layer, a source directory written by a task named `stonecutterGenerate` that nothing declares as its producer (as Stonecutter does), and one a task declares; none of them exists when the build is configured. It failed (the generated sources did not compile) before the list was chosen at execution time.
- `./gradlew -p gradle-plugin/src/test/fixtures/defaults-consumer check` proves two projects inheriting one `myceliumDefaults`.
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
so (`ship.json`'s `why`). The artifact's jar adds the ship task's classes and, only when a downgrade actually
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

