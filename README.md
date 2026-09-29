# Mycelium

**Build once, deploy everywhere: one mod, every loader and version it can reach, with evidence of what holds.**

Mycelium is a development-time machine for Minecraft mods of every size, from a server-side integration to a total
conversion. It ships no runtime. It gives a mod:

- **contracts**: typed, versioned capabilities whose laws are proven by a harness that must catch every broken
  implementation in its rejection matrix, a do-nothing one among them;
- **a Gradle plugin** that imports contract models under the mod's own namespace, gates the build on every harness,
  proves the mod's pure layer on every Java an era runs on, keeps optional integrations isolated, and writes an
  evidence manifest into every jar;
- **the tower** (`DESIGN.md` §5), a convention that keeps platform access in one layer. That layer stays native and
  as powerful as the platform allows; everything else is written once.

The founding design is `DESIGN.md` (v0.4, frozen). What belongs here is `docs/admission.md`. How to work here is
`AGENTS.md`.

| | |
|---|---|
| Status | Contract models and rejection matrices are checked by the repository build. |
| Java | Contracts and harness: written for 17, proven on every Java an era runs on (`docs/eras.md`). Plugin: built for 17. |
| Loaders | Whatever `eras/minecraft.json` lists for an era: Fabric, NeoForge and Forge on modern versions, and the loaders of older eras. The plugin knows none of them. |
| Dependencies | None at design time. The plugin uses Gson and ASM; era gates fetch JvmDowngrader and each era's JDK when needed. |

## Layout

```
mycelium/
├── DESIGN.md               founding design, v0.4 (frozen); archive/ holds earlier versions
├── AGENTS.md               how to work here: principles, map, loops, gates
├── CONTRACT-PACKAGE.md     what a contract package is made of
├── CHANGELOG.md            what changed, what ran, what was found
├── docs/                   admission.md, eras.md, integrations.md, decisions/
├── eras/                   minecraft.json (every version by era), loaders.json, versions.json
├── harness/                the shared harness (src/), its own checks (test/), sources.txt
├── contracts/              registry.json, patterns.json, and one directory per contract
├── gradle-plugin/          dev.woflo.mycelium and dev.woflo.mycelium.repository (an included build)
├── run-contracts.sh, .ps1  every harness without Gradle
└── build.gradle.kts        this repository's own gate: `./gradlew check`
```

`contracts/registry.json` lists every contract: its id, its directory, its package, and who it was written for. Each
package is `SPEC.md` plus `model/` (what a consumer imports), `fixtures/` and `harness/` (never shipped).

## Running the harnesses


Each harness prints the reference result, then every broken implementation `CAUGHT` or `MISSED` for its intended
label, then `Result: PASS|FAIL`. The exit code counts reference failures, missed rejections, and a matrix with no
`NoOp*` entry. **A green exit is one input to acceptance, never the decision** (`DESIGN.md` §11).

## Using the plugin in a mod

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


Optional blocks: `pure { }` declares the mod's pure layer, which is proven on every era and shipped downgraded where
an era needs it (`docs/eras.md`); `integration(...)` declares an optional integration, kept isolated
(`docs/integrations.md`); `ports(...)` names the mod's own packages that are meant to touch the platform, for
`myceliumSurface` (`docs/eras.md`).

| Task | Effect |
|---|---|
| `myceliumImport` | Copies each selected contract's `model` into `build/generated/sources/mycelium/java` under the mod's namespace, and adds it to `main`. A model that reaches the harness is refused. |
| `myceliumVerify` | Compiles and runs each selected harness; collects each harness's report. In `check`. `-Pmycelium.contracts=a@1,b@1` overrides the selection for one run. |
| `myceliumUsage` | Reports imported contracts the mod's own sources never use. Warns; `failOnUnused = true` fails. In `check`. |
| `myceliumIsolation` | An optional integration's API is mentioned only in its home package and its gates. Before `compileJava` and in `check`. |
| `myceliumPurity` | The pure layer mentions no platform package (P1), and reads no clock, environment or unseeded chance (P3). |
| `myceliumPureCompile` | Compiles the pure layer alone. If it compiles there, it is pure in fact. |
| `myceliumEraJava<N>` | One era's gate: every bundle on that Java's JVM (stubs, linkage P2, behaviour). `myceliumEras` runs every declared era. |
| `myceliumPureShip` | Puts the pure layer's classes into this target's jar: downgraded and shaded (from the era gate's own output) when the era needs it, a no-op when it does not (`docs/eras.md`). |
| `myceliumDowngradeJar` | The jar's own classes, not only the pure layer's, downgraded and shaded for an era older than the Java they were compiled for, then held to the era's Java: no call without a backport, every `java.*` reference present on a real JVM of it. Runs only when a consumer's loader step takes its output (`docs/eras.md`). |
| `myceliumSurface` | Reports platform references per package of the mod's own sources, split into declared `ports(...)` and everything else: the cost the next rung would have to eat (`docs/eras.md`). |
| `myceliumEvidence` | Writes `META-INF/mycelium/evidence.json` into every jar: target, era, each contract's model hash, what ran and what was not checked, with why. Design-time evidence only. |
| `myceliumRepository` | This repository only (`dev.woflo.mycelium.repository`): the repository against everything it states. |
| `myceliumRefreshVersions` | This repository only: rewrites `eras/versions.json` from Mojang's launcher manifest, by hand. |

No shared runtime is introduced. Two mods pinning different contract majors carry different private code and cannot
conflict.

## When a consumer finds a law the contract never stated

A consumer's red team finds bugs that any implementation could have made, because the contract never forbade them.
Those belong here, not only in the consumer. Add the law, a label, and a fixture that makes the mistake, and see the
harness catch it. Additive changes stay in `@N`; renaming a label or changing a required method waits for `@N+1`.
`docs/decisions/0002-what-somnium-taught.md` records the first round.

## What Mycelium does not claim

- That a contract passing its harness behaves correctly in a real game. That is the provider's gametest evidence, per
  target.
- That source portability implies save or protocol portability (`DESIGN.md` §8.6).
- That every feature translates. Native work is first-class, and is tracked as native (`DESIGN.md` §4).
