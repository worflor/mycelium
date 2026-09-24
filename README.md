# Mycelium

**A mod development machine: one intent, many substrates, explicit evidence.**

Mycelium is a development-time compiler, adaptation system and evidence registry for Minecraft mods. It does
not ship a runtime. It gives a mod three things: **contracts** (typed, versioned domain capabilities with
safety laws, positive obligations and a rejection matrix that must be caught before any provider exists),
a **Gradle plugin** that imports contract models under the mod's own namespace, runs every harness as a build
gate and writes an evidence manifest into the jar, and a **layered convention** (the tower, `DESIGN.md` §5)
that keeps platform access in one place so the same intent ships on Fabric and NeoForge across versions.

The founding design is `DESIGN.md` (v0.4, frozen for implementation). Read it once. This file is the map.


## Layout


Each contract package is `SPEC.md` + `src/mycelium/<pkg>/v1/{model,fixtures,harness}` and compiles with
`javac --release 17` plus `harness/src`. `model` is what consumers import. `fixtures` and `harness` never ship.

## Running the harnesses

Without Gradle, from this directory:


With Gradle (dogfooding the plugin):

```bash
./gradlew myceliumVerify                                     # all packages in the registry
./gradlew myceliumVerify -Pmycelium.contracts=world.copy@1   # a subset
```

Each harness prints the reference result, then the rejection matrix with `CAUGHT`/`MISSED` per deliberately
incorrect implementation, then `Result: PASS|FAIL`. Exit code is reference failures plus missed rejections.
**A green exit is one input to acceptance, never the decision** (`DESIGN.md` §11).

## Using the plugin in a mod

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../../mycelium/gradle-plugin")
    // ...repositories
}
```

`build.gradle.kts` (or a Stonecutter per-loader buildscript):

```kotlin
plugins {
    id("dev.woflo.mycelium")
}


What happens:

| Task | Effect |
|---|---|
| `myceliumImport` | Copies each contract's `model` package into `build/generated/sources/mycelium/java`, rewriting `mycelium.<pkg>.v1` to `<namespace>.<pkg>.v1`, and adds it to `main`. Models that touch the harness are rejected. |
| `myceliumVerify` | Compiles harness + each selected package with the Gradle JVM's compiler and runs `…harness.Run`. Wired into `check`. Fails the build on any MISSED rejection or reference failure. `-Pmycelium.contracts=a@1,b@1` overrides the selection for one run. |
| `myceliumEvidence` | Writes `META-INF/mycelium/evidence.json` into every jar: artifact id/version/target profile, each contract's model SHA-256 and harness status, and a claims block that says this is design-time evidence only. |

No shared Mycelium runtime is introduced. Two mods pinning different contract majors carry different private
code and cannot conflict.

## When a consumer finds a law the contract never stated

A consumer's red team will find bugs that any provider could have made, because the contract never forbade them.
Those belong here, not only in the consumer: add the law, a test label and a rejection fixture that makes the
mistake, and check that the harness catches it. Additive changes (new labels, `default` methods) stay in `@1`;
anything that renames a label or changes a required method waits for `@2`. `docs/decisions/0002-what-somnium-taught.md`
is the first round: two laws landed, one new major (`dimension.travel@2`) promoted from Somnium's own code, three proposals queued.

## Adding a contract

Follow `CONTRACT-PACKAGE.md`. Then add a row to `contracts/registry.json`. The harness must catch every
deliberately incorrect implementation, including a `NoOp*` fixture, before a provider is written anywhere.

## What Mycelium does not claim

- That a contract passing its harness behaves correctly on a real game target. That is the provider's
  gametest evidence, per target profile.
- That source portability implies save or protocol portability (`DESIGN.md` §8.6).
- That every feature translates. Native extensions are first-class and tracked separately (`DESIGN.md` §4).

## Gates (DESIGN §14) and where they stand

| Gate | State |
|---|---|
| A — Trustworthy baseline (Osmosis 1.21 pinned, Flow invariants, bad implementations rejected) | `contracts/flow` harness: reference CLEAN, 5/5 matrix entries CAUGHT. Osmosis characterization not yet run. |
| B — Toolchain preflight | Decided and evidenced in Somnium: Stonecutter 0.9.8 + loom-back-compat + ModDevGradle, four profiles configure; see `docs/decisions/0001-substrate.md`. |
| C — One vertical slice | Not started (Osmosis Flow slice). A second consumer supplied an independent vertical slice. |
| D1 — Independent reuse + coexistence | Somnium is the unrelated consumer; ten contracts authored for it, none yet reused by Osmosis. Its red teams fed two laws back into `@1` (decision 0002). |
| D2, E, F | Not started. |
