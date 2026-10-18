# How Mycelium works

Mycelium is a build-time compatibility compiler developed alongside a mod. A divide between versions or loaders becomes
a declaration, laws that say what must hold, and generated per-target artefacts, with evidence of what held. It introduces no shared
runtime. It exists at development time, between a mod's intent and the loaders and versions that run it, and grows with
the mods that use it (`docs/decisions/0015-a-compiler-you-build-with.md`).

The mod supplies its structure and behavioral contracts; Mycelium supplies the common compatibility machinery.
Existing contracts are optional reuse. New declarations and realizations are developed with the feature in the mod's
Mycelium checkout or private fork, so the compiler's capabilities grow through use. Upstream contribution is independent
of that development loop.

This page is the technical model. `README.md` is the front door, `docs/building-with-mycelium.md` is what to do when a
mod meets a divide, and `DESIGN.md` is the founding record this page descends from.

## The anatomy of a crossing

A crossing combines a pure declaration, explicit obligations, target data, and build-time verification.

| Part | What it is | Where it lives |
|---|---|---|
| **Declaration** | A pure model of what the mod means, in plain Java, with no Minecraft types. Domain-specific: each divide gets its own precise model. | `model/` of a contract package |
| **Laws** | Obligations on every target, including positive behavior that a do-nothing implementation breaks. | the package's `SPEC.md` |
| **Rejection matrix** | One broken implementation per law, ideally the bug a mod really made, and a `NoOp*`. The harness must catch every one for its own label. | `fixtures/` and `harness/` |
| **Era data** | What changes at which version, as rows the compiler reads. Never numbers in code. | `eras/` (`docs/eras.md`) |
| **Compilers** | Build-time tasks that import models, generate each target's artefacts, ship the jar. | `gradle-plugin/` (`docs/plugin.md`) |
| **Gates** | Checks of a mod: harnesses, purity, era Java, range, linkage, isolation, the repository itself. A proof fails the build; a check that explains has a level (`off`, `warn`, `fail`) the mod sets. | the table below, `docs/plugin.md` |
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
| L2b Bridges | **`bridges/`** | The native half of a divide: one adapter per realization, imported as source for the target | yes; game references linked where name data is pinned; loader APIs checked by the consumer |
| L3 Seams | the mod | Planning and composition, reaching the platform only through L2 | only through contracts |
| L4 Providers | the mod, per loader and era | Native implementations and lifecycle binding: the platform-access layer | yes, with full power |
| L5 Machine | **`gradle-plugin/`**, plus the mod's buildscripts | Import, verify, eras, isolation, evidence | n/a |
| L6 Verification | contract harnesses; the mod's gametests | Rejection matrices, era gates, real-game evidence | test-time |

Mycelium owns the contract models, reusable bridges, and build machinery (L2, L2b, L5). Each mod owns its intent,
kernels, composition, native extensions, and game-test evidence. The goal of the whole arrangement is a small platform layer
and a large pure layer, with the pure layer proven on every era automatically (`docs/eras.md`).

## Principles

These are the invariants every change keeps. Each says what keeps it.

1. **Claims discipline** (`DESIGN.md` §11, §14). A passing harness is design-time evidence: the reference model and the
   rejection matrix hold. It says nothing about a real game. Game-target evidence belongs to the mod, from its own
   gametests. The evidence manifest records what ran and what was not checked, with why, and never claims in-game
   behaviour. Documents may report attributed consumer observations, with their targets and limits; they do not extend
   those observations to untested targets. *Kept by `myceliumEvidence` and review.*
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
8. **Precision is part of the interface.** Exact ids, strict readers that refuse unknown fields, reports that say
   what they checked, and gates that name the break make the machine usable by people and agents. A convenient default
   that hides a decision is a defect here.
9. **Offer, don't police** (`docs/decisions/0017-offer-dont-police.md`). Mycelium offers a mod its compiler, its proofs and
   its data; it does not stand between the mod and its own code. A check fails by default only for silent breakage (a build
   that would ship something that does not work and say nothing); every other check warns, and every check has a level and
   per-site allowances the mod sets and the manifest records. Nothing blocks compilation: the proofs (compile alone, the era
   gates, linkage) are what the build stands on, and the text checks explain them. What the build can derive from the mod's
   own sources, the era table or Mycelium's own recorded proofs, a mod does not declare, and a declaration remains as an
   override. This does not weaken principle 8: a default that hides a decision is still a defect, so each derivation and
   each allowance is written into the evidence. *Kept by `docs/plugin.md`'s check table (every check, its default, what it
   prevents) and the self-tests that fire each level.*

## What checks what

`./gradlew check` at the repository root runs all of it. The runner scripts are the quick path for the harnesses alone.

| Gate | What it proves |
|---|---|
| `myceliumVerify` | every harness: the reference clean, every matrix entry caught for its label, a `NoOp*` among them. In a mod, only for a contract whose sources differ from what `contracts/proofs.json` (written by `myceliumRecordProofs`) says Mycelium proved |
| `myceliumDeriveContracts` | which contracts and bridges the mod takes: its own imports under its namespace (and `<namespace>.bridges.<slug>` references), or the lists it wrote; .java, .kt and .scala are scanned |
| `myceliumPackMeta` | the pack format a target version needs, written into the mod's resources from the era table |
| `myceliumPresentation` | the item presentation programs compiled to the era's files, or why an item has no selection on that version |
| `myceliumRecordProofs` | writes `contracts/proofs.json` from the reports, only for a full, passing run (never a narrowed one) |
| `myceliumBridgeImport` | the bridges a mod takes, as the variant and overlays for its target, under its namespace |
| `myceliumPurity`, `myceliumPureCompile` | the contract models are a pure layer (P1, P3), and compile alone |
| `myceliumEras` | every harness on every Java an era runs on: stubs, linkage (P2), behaviour |
| `myceliumBridgeLinkage` | every bridge variant, with each overlay, compiles against the newest version of its group that has name data and is reference-checked on the rest; it reports "linked N of M version(s), K not linked (why)", and a missing toolchain is a note |
| `myceliumBridges` | a mod's bridge override exists and implements the port (B1); a bridge with no realization on the target is reported (B2) |
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
  acyclic requirement (`docs/decisions/0014-contracts-compose-by-declared-requirement.md`). It never shares a type by accident.
- **Versions are majors.** Labels are a contract's public surface. Additions that keep every law, label and required
  method stay in `@N`; a change of meaning is `@N+1`, a new package that `supersedes` the old, and consumers move when
  they choose.

## Relationship to the founding design

`DESIGN.md` (v0.4) is the founding record: the thesis, the audit that motivated it, the tower, the first contract. It is
frozen as historical rationale rather than a description of every implemented capability. Decisions that go beyond it
are in `docs/decisions/`, and where a decision and the design differ, the decision is current. The largest such step is decision 0015: the design's
"promote a native pattern to a contract on evidence" became "grow Mycelium when a mod meets a divide".
