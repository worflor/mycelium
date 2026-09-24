# Agent Onboarding Guide: Mycelium

This document orients AI agents, autonomous workers and human contributors to Mycelium. It says what the
project is, what state it is in, what you may touch, how to prove your work, and where the bodies are buried.
No `CLAUDE.md` is used in this workspace — `AGENTS.md` is the onboarding doc, here and in every project folder.

---

## 1. Project Identity


**One-liner:** Mycelium gives a Minecraft mod three things: typed **contracts** with safety laws, positive
obligations and a rejection matrix that must be caught before any provider exists; a **Gradle plugin** that
imports contract models under the mod's own namespace, runs every harness as a build gate, and writes an
evidence manifest into the jar; and a **layered convention** (the tower) that keeps platform access in one
place so the same intent ships on multiple loaders across multiple versions.

## 2. Read This First

### 2.1 State of the project

As of 2026-09-23 (evening): twelve contract packages exist (`dimension.travel@2` promoted from Somnium), all PASS with full reference-and-rejection-matrix coverage
(three laws added from Somnium's red teams, `docs/decisions/0002-what-somnium-taught.md`; `myceliumUsage` checks imports are real;
`contracts/registry.json` `liveConsumers` says who actually uses what; `CHANGELOG.md` has the rest)
(`bash run-contracts.sh` or `./gradlew myceliumVerify`). The Gradle plugin (`gradle-plugin/`) works and is
applied in both of Somnium's loader buildscripts. Gate A (Osmosis characterization) has not been run. Gate B
(substrate choice) is decided and evidenced — see `docs/decisions/0001-substrate.md`. Gates C onward (one
vertical slice inside Osmosis, independent reuse proven by a second consumer) are partially met by Somnium's
existence as a second, unrelated consumer, but no contract has yet been reused *back* into Osmosis. Read
`README.md` for the map and `DESIGN.md` (v0.4, frozen for implementation) for the founding design — read that
once, in full, before proposing an architectural change.

### 2.2 Claims discipline

This is the load-bearing rule of the whole project (`DESIGN.md` §11, §14).


### 2.3 The three non-negotiables

1. **Every contract's harness must reject every deliberately incorrect implementation in its rejection
   matrix, including a `NoOp*` fixture, before any provider is written anywhere.** Safety alone is
   satisfiable by doing nothing (a transfer that always moves zero preserves every item); positive
   obligations are what make a no-op fail. See `DESIGN.md` §8.3.
2. **No shared Mycelium runtime.** Imported contract models live under the *consumer's* artifact-owned
   namespace (`myceliumImport`'s `namespace` parameter), privately, per artifact. Two mods pinning different
   contract majors must carry different private code and never conflict. See `DESIGN.md` §12.
3. **No premature IR.** Typed records, sealed outcomes and small plans. Do not build a general effect
   language, event bus abstraction or DSL because it feels tidy — `DESIGN.md` §7 says why, and the abandonment
   criteria in §13 apply to any abstraction that starts costing more than the duplication it replaces.

### 2.4 The one-sentence architecture

Contracts promise (laws plus positive obligations, proven by a harness); providers implement and touch the
platform; a Gradle plugin imports the promise into a consumer's own namespace, gates the build on the harness,
and records what actually happened.

## 3. Architecture Overview

### 3.1 The tower (`DESIGN.md` §5)

| Layer | Where | What lives here | Touches Minecraft/loader? |
|---|---|---|---|
| L0 Intent | a consumer's `intent/`, `GDD.md`/`TDD.md` | Authored behavior, feel criteria, feature identifiers | no |
| L1 Kernels | a consumer's `kernels/` (e.g. Somnium's) | Pure algorithms over explicit inputs | **never** |
| L2 Contracts | **`contracts/` in this repo** | Typed, versioned domain capabilities and their laws | **never** |
| L3 Seams | a consumer's `common/` | Reusable planning, effect composition, referencing platform types only through L2 | only through contracts |
| L4 Providers | a consumer's per-loader source | Native implementations, lifecycle binding, the designated platform-access layer | yes, and only here |
| L5 Machine | **`gradle-plugin/` in this repo**, plus a consumer's build scripts | Import, verify, evidence, dependency resolution | n/a |
| L6 Verification | contract `harness/`, a consumer's gametests | Rejection matrices, differential traces, feel checks | test-time |

Mycelium proper is L2 and L5. Everything else lives in the consumer.

### 3.2 Repository layout

```
mycelium/
├── DESIGN.md                 founding design, v0.4 (frozen); archive/ holds v0.1–v0.3 for history
├── README.md                 the map: layout, running harnesses, using the plugin, gate status
├── CONTRACT-PACKAGE.md        how a contract package must be laid out — read before adding one
├── AGENTS.md                  this file
├── docs/decisions/            one architectural decision per file, dated, with evidence
├── harness/src/mycelium/harness/
│   ├── Check.java             labeled failures; a label is part of a contract's public surface
│   ├── Suite.java             named fixtures → report → exit code
│   ├── ContractRun.java       reference must be CLEAN; every rejection-matrix entry must be CAUGHT
│   └── Seeded.java            deterministic variety for property-style fixtures
├── contracts/
│   ├── registry.json          id → path, package, status, firstConsumer — the plugin reads this
│   └── <slug>/                one per contract; see §3.3 and CONTRACT-PACKAGE.md
├── gradle-plugin/              dev.woflo.mycelium, an included Gradle build
│   └── src/main/java/dev/woflo/mycelium/gradle/
│       ├── MyceliumPlugin.java        wires the three tasks below into a consumer's build
│       ├── MyceliumExtension.java     the `mycelium { }` DSL block
│       ├── ContractRegistry.java      reads contracts/registry.json
│       ├── ImportContractsTask.java   copies+renamespaces model/ into the consumer
│       ├── VerifyContractsTask.java   compiles+runs each selected harness; gates `check`
│       ├── EvidenceTask.java          writes META-INF/mycelium/evidence.json
│       └── Fingerprint.java           stable SHA-256 over a directory, for task inputs
├── run-contracts.sh / .ps1    compile + run every harness without Gradle
└── build.gradle.kts           dogfoods the plugin on this repo itself (`./gradlew myceliumVerify`)
```

### 3.3 One contract package

```
contracts/<slug>/
├── SPEC.md                         normative: scope, types, outcomes, laws, positive obligations,
│                                    edge semantics, traits, fixtures table, rejection matrix, versioning,
│                                    explicit non-claims (mirror contracts/flow/SPEC.md)
└── src/mycelium/<slug_with_underscores>/v1/
    ├── model/                       the contract — the ONLY package a consumer ever imports
    ├── fixtures/                    reference implementation AND every incorrect implementation
    │                                named in the rejection matrix — never shipped to a consumer
    └── harness/
        ├── <Name>ContractTests.java    fixtures T1..Tn against an implementation
        └── Run.java                    main(): mycelium.harness.ContractRun.run(id, reference, matrix)
```


## 4. Layer Rules (hard rules)

1. `contracts/*/src/*/model` and `contracts/*/src/*/fixtures` have **no** Minecraft or loader dependency, ever.
   If a contract needs a Minecraft type, it's not a Mycelium contract — that decision belongs in the
   consumer's L3/L4.
2. `fixtures/` and `harness/` are **never** imported by a consumer. `ImportContractsTask` only copies
   `model/`, and rejects (build failure) any model file that imports `mycelium.harness` — see
   `ImportContractsTask.rewrite`/the harness-dependency check.
3. Outcomes are a sealed interface: `Committed(receipt) | Rejected(reason) | Unsupported(detail)`, plus
   `Stale`/`Indeterminate` where the contract's staging genuinely has that state. Zero effect is a rejection,
   never a receipt (`DESIGN.md` §8.3, §14's decisive counterexamples).
4. Every contract needs **both** safety laws and positive obligations, and a rejection matrix that catches a
   `NoOp*` fixture via a positive-obligation label, not just a safety one.
5. Labels (`"T3:some-label"`) are part of a contract's public surface. Don't rename one without bumping the
   contract's version — other code (a consumer's tests, this repo's own CI expectations) may reference it.
6. The Gradle plugin (`gradle-plugin/`) never gains Minecraft-specific logic. It knows about `.java` files,
   packages and Gradle tasks — nothing loader-specific. If a task needs to know about Fabric or NeoForge,
   that's the consumer's build script's job, not the plugin's.
7. `registry.json` is the only place a contract's id, path and package are declared together. Add a row here
   in the same change that adds the package; nothing should hardcode the mapping elsewhere.

## 5. Conventions

### 5.1 Java

- Java 17 for everything under `contracts/` and `harness/` (`javac --release 17`). The plugin targets 17 too
  (built on whatever JDK runs Gradle; see `gradle-plugin/build.gradle.kts`).
- Records for data, sealed interfaces for outcomes, `switch` expressions. No Lombok, no third-party deps in
  contracts (the plugin's Gson dependency stays inside the plugin, never leaks into a contract's model).
- A `Tx` (or `*Tx`) type per contract follows `contracts/flow`'s `FlowTransaction` pattern exactly: open,
  openNested, commit, abort, registerUndo, onOuterCommit. Copy it, don't invent a new transaction shape.
- Naming: contract ids are `domain.capability@N`; model types are nouns; a contract's top-level static
  executor class is named after the contract's verb (`Transfer`, `SleepLifecycle`, `WorldCopy`, `RuleEffects`).

### 5.2 Docs

- `DESIGN.md` is the source of truth for *why*. `README.md` for *how to use this repo*.
  `CONTRACT-PACKAGE.md` for *how a package must be shaped*. This file for *how to work here*. When two
  disagree, say so in the change and fix the doc with the reason — don't silently pick one.
- Decisions with rationale go in `docs/decisions/NNNN-<slug>.md`, one per file: date, status, context, options
  considered, decision, evidence, consequences. `0001-substrate.md` is the template to follow.
- Keep this file current. If you add a folder, a task, a gotcha or a command, add it here in the same change.

## 6. How To Work Here

### 6.1 The loop for adding or changing a contract

```
1. Does DESIGN.md's ontology (§3, §6) actually call for a new contract, or does an existing one cover it
   with a version bump? A new contract id is a design decision, not a code change — say so if unsure.
2. Follow CONTRACT-PACKAGE.md exactly. Write SPEC.md first: scope, types, outcomes, laws (L1..Ln), positive
   obligations (P1..Pn), edge semantics, traits, a normative fixtures table, a rejection matrix.
3. Write model/ (pure, no Minecraft, no harness dependency), then fixtures/ (reference + every incorrect
   implementation named in the rejection matrix), then harness/Run.java.
4. Compile and run: see §11. Iterate until Result: PASS with every matrix entry CAUGHT — including the
   reference itself being CLEAN.
5. Add a row to contracts/registry.json.
6. If this repo's own build.gradle.kts verifies "every contract in the registry" (no explicit list), it
   picks the new one up automatically — confirm with `./gradlew myceliumVerify`.
7. Tell the consumer(s) that would use this contract; don't wire it into a consumer's build yourself unless
   asked — that's a scope decision for that project.
```

### 6.2 Agents propose; protected policies decide

- Rejection matrices, safety laws, positive obligations and `registry.json` are **protected**. Propose
  changes in a clearly labeled change; never weaken a law or delete a rejection-matrix entry to make your own
  work pass. `DESIGN.md` §12 is explicit: a repair must not authorize itself.
- Never mark a fixture as skipped or delete one to get green. If a fixture genuinely can't pass yet, that's a
  design conversation (is the contract wrong, or the implementation?), not something to quietly drop.
- Never claim two loaders/versions behave identically without both traces existing somewhere (a consumer's
  job, but don't write claims-discipline-violating text in this repo's own docs either).

### 6.3 Scope boundaries for autonomous agents

You may, without asking:
- Create and edit files inside `contracts/`, `harness/`, `gradle-plugin/`, `docs/decisions/`.
- Add a new contract package end to end, including its registry row.
- Run `run-contracts.sh`/`.ps1` and `./gradlew myceliumVerify` locally.
- Fix a bug in the Gradle plugin, with tests/verification of the fix.


### 6.4 Reporting


## 7. Common Tasks

### Add a contract
See §6.1. The harness must catch every incorrect implementation *before* any provider anywhere depends on it.

### Fix a failing rejection-matrix entry
1. Read the fixture's own doc comment — it should say what law or obligation it's meant to violate.
2. Run just that package: `bash run-contracts.sh <slug>` (or `.ps1`). Read the CAUGHT/MISSED line and the
   failing label underneath it.
3. Fix either the reference executor (if the law is right but the code is wrong) or the fixture (if the
   fixture doesn't actually violate what it claims to) — never both to force a pass; figure out which one
   is actually incorrect against `SPEC.md`.

### Change the Gradle plugin
1. `gradle-plugin/` is an included build (`pluginManagement { includeBuild("gradle-plugin") }` in this repo's
   `settings.gradle.kts`, and in a consumer's `settings.gradle.kts` via a relative path).
2. After a change, `./gradlew myceliumVerify` here dogfoods it against every registered contract. A
   consumer's own build (e.g. Somnium's `:26.3-fabric:build`) is the second, independent proof it still works
   — run at least one before calling a plugin change done.
3. `ImportContractsTask`, `VerifyContractsTask` and `EvidenceTask` are the only three tasks the plugin wires
   into a consumer. Keep it that way; don't grow a fourth without a concrete need from a consumer.

### Add a decision record
Copy `docs/decisions/0001-substrate.md`'s structure. One decision, one file, dated, with the evidence that
justified it — not a design discussion, a record of what was decided and why.

## 8. Verification Checklist (before calling anything done)

- [ ] Every contract package's harness: `Result: PASS`, reference `CLEAN`, every rejection-matrix entry
      `CAUGHT` for its *intended* label (not just "some" failure)
- [ ] `bash run-contracts.sh` (all packages) and `./gradlew myceliumVerify` both agree
- [ ] No model file imports `mycelium.harness` (the import task would reject it, but check before, not after)
- [ ] `contracts/registry.json` updated in the same change as any new/moved/renamed package
- [ ] No claim in a commit message, doc or report says a contract "works" in-game — that evidence belongs to
      a consumer, not here
- [ ] Docs updated in the same change: this file if you added a folder/task/gotcha, `README.md` if the gate
      table or usage changed, a decision record if you made an architectural call

## 9. Known Gotchas


## 10. File Map for Quick Navigation


## 11. Commands

Without Gradle:


With Gradle (dogfoods the plugin on this repo):

```bash
./gradlew myceliumVerify                                     # every package in the registry
./gradlew myceliumVerify -Pmycelium.contracts=world.copy@1   # a subset
```

Building the plugin alone: `./gradlew :gradle-plugin:build` (or just build a consumer that applies it — that
exercises the same code path plus real-world integration).

## 12. Open Questions

- Gate A (Osmosis characterization) hasn't started: no contract has been reused *back* into Osmosis yet, so
  the "independent reuse" claim (Gate D1) is one-directional (Somnium reusing Mycelium) until that happens.
- Contract format and corpus storage beyond the current file-tree-plus-registry.json approach: still open per
  `DESIGN.md` §15.
- Whether `persistence.player-state@1` and `presentation.particle-cue@1` (authored for Somnium) get adopted
  back into Osmosis, or stay Somnium-only: whoever needs it next decides, per `DESIGN.md`'s incremental
  adoption principle.

## 13. Resources


---

*Keep this file current. When a contract's actual behavior disagrees with its `SPEC.md`, the spec changes and
the reason is written down — the spec is normative, but only because it's kept honest. When in doubt, read
`DESIGN.md`. When `DESIGN.md` doesn't say, that's a decision to make and record, not to guess past.*
