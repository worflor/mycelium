# How Mycelium works

Mycelium is a compatibility compiler a mod builds with. A divide between versions or loaders becomes a declaration,
laws that say what must hold, and generated per-target artefacts, with evidence of what held. It ships no runtime. It
exists at development time, between a mod's intent and the loaders and versions that run it, and it grows with the mods
that use it (`docs/decisions/0015-a-compiler-you-build-with.md`).

This page is the technical model. `README.md` is the front door, `docs/building-with-mycelium.md` is what to do when a
mod meets a divide, and `DESIGN.md` is the founding record this page descends from.

## The anatomy of a crossing

Every divide Mycelium has crossed has the same parts. The parts are the machine; the contracts in `contracts/` are what
the machine produced.

| Part | What it is | Where it lives |
|---|---|---|
| **Declaration** | A pure model of what the mod means, in plain Java, with no Minecraft types. Domain-specific: each divide gets its own precise model. | `model/` of a contract package |
| **Laws** | Sentences that must hold on every target. Each one is a sentence a do-nothing implementation breaks. | the package's `SPEC.md` |
| **Rejection matrix** | One broken implementation per law, ideally the bug a mod really made, and a `NoOp*`. The harness must catch every one for its own label. | `fixtures/` and `harness/` |
| **Era data** | What changes at which version, as rows the compiler reads. Never numbers in code. | `eras/` (`docs/eras.md`) |
| **Compilers** | Build-time tasks that import models, generate each target's artefacts, ship the jar. | `gradle-plugin/` (`docs/plugin.md`) |
| **Gates** | Checks that fail the build: harnesses, purity, era Java, range, linkage, isolation, the repository itself. | the table below |
| **Evidence** | What ran, what was not checked and why, written into the jar. | `META-INF/mycelium/evidence.json` |

A mod that imports nothing still gets the gates and an evidence manifest. A mod that imports one contract carries that
contract's model, privately, under its own namespace.

## The tower

The tower (`DESIGN.md` §5) keeps platform access in one layer, so everything else is written once.

| Layer | Where | What lives here | Touches Minecraft? |
|---|---|---|---|
| L0 Intent | the mod | Authored behaviour, feel criteria, feature identifiers | no |
| L1 Kernels | the mod's pure layer | Pure algorithms over explicit inputs | **never** |
| L2 Contracts | **`contracts/`** | Typed, versioned declarations and their laws | **never** |
| L3 Seams | the mod | Planning and composition, reaching the platform only through L2 | only through contracts |
| L4 Providers | the mod, per loader and era | Native implementations and lifecycle binding: the platform-access layer | yes, with full power |
| L5 Machine | **`gradle-plugin/`**, plus the mod's buildscripts | Import, verify, eras, isolation, evidence | n/a |
| L6 Verification | contract harnesses; the mod's gametests | Rejection matrices, era gates, real-game evidence | test-time |

Mycelium proper is L2 and L5. The rest lives in each mod. The goal of the whole arrangement is a small platform layer
and a large pure layer, with the pure layer proven on every era automatically (`docs/eras.md`).

## Principles

These are the invariants every change keeps. Each says what keeps it.

1. **Claims discipline** (`DESIGN.md` §11, §14). A passing harness is design-time evidence: the reference model and the
   rejection matrix hold. It says nothing about a real game. Game-target evidence belongs to the mod, from its own
   gametests. The evidence manifest records what ran and what was not checked, with why, and never claims in-game
   behaviour; neither may any document or commit here. *Kept by `myceliumEvidence` and review.*
2. **A no-op fails every contract.** Safety alone is satisfiable by doing nothing, so every contract has laws a
   do-nothing implementation breaks (`DESIGN.md` §8.3). *Kept by `ContractRun`.*
3. **No shared runtime.** Imported models live under each mod's own namespace, privately. Two mods pinning different
   majors never conflict (`DESIGN.md` §1, §12). *Kept by `myceliumImport`.*
4. **Native is first-class.** A mod on Mycelium can do anything a mod without it can: mixins, access wideners, raw
   packets, rendering, world generation, another mod's API. Providers call the platform directly, through no wrapper of
   ours. Mycelium makes the platform layer small, never hidden (`DESIGN.md` §4).
5. **Declarations are domain-specific, never universal.** There is no effect language, event bus or runtime DSL. There
   are many small compilers that share one machine: era data, import, verification, evidence
   (`docs/decisions/0015-a-compiler-you-build-with.md`).
6. **The plugin knows no loader.** Loader and version knowledge is data (`eras/minecraft.json`, `eras/loaders.json`,
   `eras/names.json`) or a mod's buildscript. A task reads sources, bytecode, the registry and the era table, never a
   loader's API (`docs/decisions/0001-substrate.md`, `docs/decisions/0003-forge.md`).
7. **Everything stated is derived or checked.** If the repository says it, the build fails when it stops being true
   (`docs/decisions/0004-the-repository-checks-itself.md`). Prose is the one exception, and it follows the rules in
   `docs/admission.md`.
8. **Correct over friendly.** Agents are Mycelium's main users, so precision is a feature: exact ids, strict readers
   that refuse unknown fields, reports that say what they checked, gates that name the break. A convenient default that
   hides a decision is a defect here.

## What checks what

`./gradlew check` at the repository root runs all of it. The runner scripts are the quick path for the harnesses alone.

| Gate | What it proves |
|---|---|
| `myceliumVerify` | every harness: the reference clean, every matrix entry caught for its label, a `NoOp*` among them |
| `myceliumPurity`, `myceliumPureCompile` | the contract models are a pure layer (P1, P3), and compile alone |
| `myceliumEras` | every harness on every Java an era runs on: stubs, linkage (P2), behaviour |
| `myceliumRange` | an artifact's claimed Minecraft range against the era table, its proofs and its loader metadata |
| `myceliumLinkage` | every reference the finished artifact makes resolves in the game's published names on each version of its range that has name data |
| `myceliumRepository` | registry against disk, package shapes, model ownership, copied patterns, era coverage, loader packages, document counts, paths and maps, and the harness's own checks |
| `selfTest` (`gradle-plugin`) | every repository check fires on the break it exists for; era, registry, evidence and integration semantics |
| `bash run-contracts.sh`, `.\run-contracts.ps1` | the harness's own checks, then every package, without Gradle |

A green gate is one input to acceptance, never the decision (`DESIGN.md` §11). Skipping a fixture to get green is
insufficient evidence, not a pass. Never weaken a law or delete a matrix entry to make your own work pass: a repair must
not authorize itself (`DESIGN.md` §12).

## Why contracts are shaped as they are

A contract package is a promise (`model/`), an implementation that keeps it and implementations that break it
(`fixtures/`), and the harness that proves both (`harness/`). Its parts and rules are `CONTRACT-PACKAGE.md`. Two choices
carry the design:

- **Vocabularies are each package's own.** Ten `Reason` enums look like duplication; they share a name, not a meaning
  (`docs/decisions/0005-what-a-contract-is-made-of.md`). A contract that builds on another says so, with a declared,
  acyclic requirement (decision 0014, contracts compose by declared requirement). It never shares a type by accident.
- **Versions are majors.** Labels are a contract's public surface. Additions that keep every law, label and required
  method stay in `@N`; a change of meaning is `@N+1`, a new package that `supersedes` the old, and consumers move when
  they choose.

## Relationship to the founding design

`DESIGN.md` (v0.4) is the founding record: the thesis, the audit that motivated it, the tower, the first contract. It is
frozen, and it stays accurate about why Mycelium exists. Decisions that go beyond it are in `docs/decisions/`, and where
a decision and the design differ, the decision is current. The largest such step is decision 0015: the design's
"promote a native pattern to a contract on evidence" became "grow Mycelium when a mod meets a divide".
