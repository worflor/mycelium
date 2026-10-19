> Publication edition: consumer source inventories, implementation findings, and audit commands are redacted. The architectural record is retained; complete source-audit records remain private.

# Mycelium — Founding Design Document

**A mod development machine: one intent, many substrates.**

| | |
|---|---|
| Status | Draft v0.1 (founding) |
| Date | 2026-09-20 |
| Working name | Mycelium (provisional) |
| First reference implementation | Osmosis; private source-audit details omitted |
| Scope | Develop and maintain Minecraft mods across every loader and every game version; start with Osmosis, scale to any mod or idea at any depth |

---

## 0. TL;DR

- A mod is a bundle of **effects against a shared mutable world**, expressed through extension points. Its *intent* is invariant. Its *expression* varies along exactly five axes: loader, MC version, resource format, Java version, build tooling.
- The system maximizes the invariant surface and makes the variance a **small, declared, generated, verified artifact**.
- Deep architecture: **8 semantic primitives → derived seams → effect IR core → delta-based ports → a compounding corpus**.
- Ports become **delta applications**. Correctness becomes **differential equivalence modulo a declared deficit**. Support becomes **computed and demand-driven**.
- The flywheel: every port enriches every future port, for every mod. That is the emergence.

---

## 1. Vision & goals

Build a development system whose marginal cost of adding a target (new loader, new version) or a new mod approaches zero over time, without lying about the hard parts.

**Goals**

1. Osmosis on every meaningful loader and version, cheaply, as the first proof.
2. The same machine serves the next mod — built from scratch or adopted into the system.
3. Low runtime dependency, light artifacts. No framework tax on users.
4. Agent-operable: machines do the matrix work; deterministic verification gates every action.
5. A compounding corpus: deltas, rules, primitive backends, and tests accumulate value across mods and versions.

**Non-goals**

- A general-purpose modding DSL (the MCreator/KubeJS trap). The system is a *pattern library and machine*, not a language for non-programmers.
- Replacing the build ecosystem (Gradle, Loom, NeoGradle/ModDevGradle). We adopt the substrate and own the layers above it.
- Magic for rendering/UI churn. T3/T4 mods cost real work; the system makes that work visible, measured, and shared — not absent.

---

## 2. Why this exists

### 2.1 Initial consumer evidence (redacted)

The first consumer exposed a small, enumerable platform surface. Its source inventory and implementation findings are omitted from this publication edition.

### 2.2 General lesson

> **Two ports can perfectly agree while preserving the same reference defect.**

The source-specific findings supporting this lesson remain in the private audit record.

### 2.3 The era boundary

- 1.21.11 (Dec 2025) was the last obfuscated release with yarn/mojmap mappings.
- 26.1+ ships deobfuscated jars; the game breaks its API on **quarterly drops**; Java 25; Loom renamed (`net.fabricmc.fabric-loom`).
- Consequence: naming hops are now largely mechanical (deterministic mapping joins), and semantic deltas are the remaining cost. That is precisely the surface a delta corpus should own.

---

## 3. Initial consumer evidence (redacted)

The initial consumer had a limited platform surface. Its source inventory and implementation findings are omitted from this publication edition.

---

## 4. The ontology: what a mod is

A mod is a bundle of **effects against a shared mutable world**, expressed through extension points.

**Intent (invariant)** — content definitions, rules (`event ∧ condition → action`), presentation style, persistence semantics, balance numbers.

**Expression (variant)** — metadata, registration, resources/data, behaviour hooks, state storage, protocol, presentation, commands, config.

The system's job: **maximize intent, minimize expression, generate the difference.**

This is also the answer to "what modding functionally is": *rules + content + effects*. Maximize the declarative (rules/content); minimize the imperative-per-target (effects); and make the imperative surface a small, typed, compiler-checked vocabulary.

---

## 5. Strategy taxonomy

Choose per feature, not per project:

1. **Interpret** — push intent into data/resources + a tiny stable shim. Content mods are ~90% this. Near-free, scales infinitely.
2. **Adapt** — core semantics + per-target primitive backends. The main path for behaviour.
3. **Translate** — ship one target; let compatibility layers translate (Sinytra Connector + Forgified Fabric API for Fabric→NeoForge; ReForged for NeoForge→Forge; Porting Lib for Forge→Fabric). Zero effort, third-party risk, edge cases. Bonus channel, never foundation.
4. **Vendor** — adopt someone's shim for a subsystem.

A mod typically mixes all four. The machine's first analytical job (via the seam auditor) is classifying each part of a mod into the cheapest viable strategy.

---

## 6. Architecture: the tower

```
L0  Intent spec         declarative; one per mod; the design doc and the build spec are the same artifact
L1  Core                pure semantics; no loader imports; unit-testable without a game
L2  Primitives          ~8 semantic verbs; targets implement these, nothing else
L3  Seams (derived)     library code over primitives (Flow, Interaction, Presentation, ...)
L4  Targets             (mc × loader): primitive impls + manifest + generated resources + metadata
L5  Machine             spec→matrix, auditor, generators, delta registry, rename bridge, ledger, CI, publisher
L6  Verification        unit → server gametest → client gametest → manifest truth → feel checklist
```

**Budget rule:** if a target exceeds its line budget, the core leaked. Fix the core, not the target.

**Compiler-enforced completeness:** every target must implement every primitive it declares (Kotlin Multiplatform `expect`/`actual` discipline, enforced by build checks). No silent gaps.

---

## 7. Primitives: the eight verbs

Every mod, at any depth, only ever does eight things to the game:

| Primitive | Meaning | Example |
|---|---|---|
| `Observe` | Receive events/hooks | A tick or interaction |
| `Query` | Read world/state | Container lookup |
| `Mutate` | Change world/state | Item transfer |
| `Register` | Add content | A content definition |
| `Persist` | Saved state / config | A saved value |
| `Send` / `Receive` | Protocol | A state update |
| `Present` | Render / particles / sound | Operation feedback |

**Why this matters:**

- Target cost is **O(8)**, independent of feature count. The 55th feature adds no target work.
- A target that cannot provide a primitive (server-only jar, no client) *declares it absent*; the feature model prunes everything downstream automatically.
- Storage backends can realize `Query`/`Mutate` through Fabric `Storage`, NeoForge `ResourceHandler`, or vanilla `Inventory`.

**This is the highest-stakes decision in the system.** If the verbs are wrong, everything downstream inherits the error. Spend disproportionate effort here.

---

## 8. Effects: the core emits a reified stream

The core does not call APIs. It emits an effect program:

```
Observe(use-block) → Query(container) → Mutate(transfer) → Send(visual) → Present(particles)
```

Targets are **interpreters** for the effect stream. What emerges:

- **Tests without a game.** Effect scripts replay in JUnit in milliseconds. Planners and sorters become pure logic, testable without booting Minecraft.
- **Semantic differential testing.** Same effect program, two interpreters (Fabric vs NeoForge-modern); compare *effect traces*. Divergence = interpreter bug **or a declared deficit** — machine-checkable.
- **Version diffs land in interpreters only.** The 1.20.5 payload rewrite, the NeoForge transfer rework — interpreter diffs. The core never moves.
- **Production replay.** A user bug report is an effect trace, replayable locally.

Java has no algebraic effects; implement as sealed interfaces + interpreters. The theory is optional; the shape is essential.

---

## 9. Deltas: the compounding corpus

Ports are not events; they are **delta applications**. This is delta-oriented programming (DeltaJ/DeltaJava, Schaefer et al.): *product = core + deltas; variants are generated, not maintained.*

- A version hop is a **delta module**: rename set, primitive/interpreter patches, resource-format changes, capability changes.
- Deltas are **bidirectional** — the same corpus yields *backports* (1.21 → 1.20.1), which the community wants and nobody has.
- Deltas are **shared across mods**. Osmosis's Flow delta and the next mod's Flow delta are the same artifact.
- Deltas carry **provenance and refusal records** ("Connector missing 1.21.2–1.21.11"; "bundle absent <1.17"). The machine never retries dead ends and always knows the frontier.

**Illustrative delta sketch:**

```yaml
hop: "1.21.1 -> 1.21.11"
primitives:
  Send:
    change: rename
    detail: "CustomPayload -> CustomPacketPayload; StreamCodec shape unchanged"
  Present: { change: none }
capabilities: []
resources: []
provenance:
  verified: [compile, gametest]
  refusals: []
```

**Illustrative intent spec:**

```yaml
# Consumer-specific manifest example redacted from the publication edition.
```

---

## 10. What starts emerging

Outcomes nobody explicitly designed, from local rules plus compounding data:

1. **Computed support matrices.** Capabilities as a *feature model*; targets as constraints; the matrix is SAT-solved, not maintained. (Feature-model SAT analysis is proven easy in practice — Mendonça/Wąsowski/Czarnecki.)
2. **Lazy targets / demand-driven builds.** Don't prebuild 40 jars. A user requests 1.19.2-Forge; the machine synthesizes (deltas are cheap), verifies, caches, publishes. The long tail costs nothing until demanded.
3. **Predictive maintenance.** The delta corpus + churn history forecasts which classes move every drop. The machine warns which seams are at risk *before* the next drop ships.
4. **Test synthesis.** Capability manifest → generated gametests. Coverage grows without hand-writing tests.
5. **Portfolio scheduling.** PSA history + Modrinth adoption data + loader share → cost/benefit model. Which mod × target pairs are worth maintaining, computed.
6. **Agent swarm, deterministically gated.** Everything (ledger, deltas, graph, tests, audit) exposed as tools; agents do the matrix work; compile + gametest + manifest-truth gates every action. The human does intent and feel.

---

## 11. Formal grounding

| Idea | Field | Take |
|---|---|---|
| Deltas as first-class | Delta-oriented programming (DeltaJ/DeltaJava) | Take the idea; the Eclipse tooling is dead; build the delta format |
| Computed matrices | Feature-model analysis (SAT) | Use an off-the-shelf solver or simple constraint code |
| Forward + backward ports | Bidirectional transformations / lenses | Take the discipline: round-trip laws become tests |
| Effect IR | Algebraic effects / free monads | Sealed interfaces + interpreters |
| Differential correctness | Differential testing + trace equivalence | Baseline divergence maps as the quality bar |
| Lazy builds | Demand-driven build systems (Mokhov et al.) | Remote caching at mod scale |

---

## 12. Precedents

- **LuckPerms** (2.3k★): `api` / `common` / per-platform modules (bukkit, bukkit-legacy, bungee, fabric, forge, neoforge, sponge, velocity, nukkit, hytale, standalone). Thin platform shells; legacy as a separate module; unit tests in common, integration in standalone. The platform axis, solved.
- **ViaVersion** (1.6k★): `api` / `common` / `build-logic` / platforms / `universal`. Proves the **translate** strategy at protocol scale.
- **Unimined**: widest build substrate — Fabric, Quilt, Forge, NeoForge, Cleanroom, Rift, LiteLoader, CraftBukkit, Spigot, Paper, Risugami's ModLoader, plain jarmodding, plus ReIndev and Better Than Adventure. LTS branch policy. Gaps: datagen is TODO.
- **Stonecutter / Modstitch / Blahaj**: version nodes + native loader toolkits; loader unification; turnkey multi-version.
- **Sinytra Connector + Forgified Fabric API**: Fabric mods on NeoForge/Forge. Coverage: 1.20.1, 1.21, 1.21.1, 26.1.x. Gap: 1.21.2–1.21.11. Bonus channel, never foundation.
- **NeoForge Transfer Rework**: `ResourceHandler<ItemResource>`, `Capabilities.Item.BLOCK/ENTITY/ITEM`, `BlockCapabilityCache`. Structurally isomorphic to Fabric's `Storage` API.
- **Fabric testing stack**: `fabric-gametest-api-v1`, `fabric-client-gametest-api-v1` (simulated input, screenshot verification), Fabric Loader JUnit. NeoForge: `runGameTestServer` exits with failed-test count.
- **Forgix / ReForged / Unified-Multiloader-Template**: jar merging, bytecode translation, and a (toy) single-jar template. Distribution tricks, not architecture.
- **ModForge / mc-mod-porter / ModPorter**: deterministic rename bridging (with MCP for agents), per-hop knowledge bases, ruleset pipelines with regression protection. Mine these for the delta corpus and rename bridge.

---

## 13. Build vs adopt

| Component | Decision | Why |
|---|---|---|
| Build substrate (remapping, Gradle) | **Adopt** (Unimined, or Stonecutter + Modstitch) | Rebuilding is a tar pit |
| Primitive vocabulary + seam library | **Build** | Nobody's abstraction matches; the crown jewel |
| Spec / manifest / generator / auditor / PSA | **Build** | The actual ecosystem gap |
| Delta registry + ledger | **Build** | The compounding asset; must be ours |
| Rename bridge | **Adopt/adapt** (ModForge-style joins) | Mechanical |
| Verification frameworks | **Adopt** (gametest, client gametest, JUnit) | Solved substrate |
| Translation distribution | **Adopt** (Connector/FFAPI) | Bonus channel |
| Jar merging | **Optional** (Forgix) | Per-target artifacts stay primary |

**Repo topology:** the machine is its own product (Gradle plugin suite + CLI). Mods stay independent repos that consume it. Monorepo-ing all mods couples them and kills the scale story.

---

## 14. Tiers and the adoption path

| Tier | Kind | Seams needed | Cost per target |
|---|---|---|---|
| T0 | Data/content | generator only | near-zero |
| T1 | Registration | `Register`, `Resources` | days |
| T2 | Behaviour | `Observe`, `Query`, `Mutate`, `Persist` | the main path |
| T3 | Presentation | + `Present` (hard: render/UI churn) | real work; budget honestly |
| T4 | Protocol/mixin-heavy | full set + client gametests | most expensive |

**Adoption of an existing mod:** seam auditor → portability report → extraction plan → intent spec. (The Osmosis audit in §3 was this process done by hand; the auditor automates it.)

**New ideas:** start at L0. The spec is the design doc; scaffolding is generated. Same machine, different tier.

---

## 15. Verification ladder

1. **Unit** — effect-script tests, no game boot (milliseconds).
2. **Server gametest** — capability assertions per target; NeoForge `runGameTestServer` exit code as CI gate.
3. **Client gametest** — input simulation + screenshot verification for the feel-critical parts.
4. **Manifest truth** — "sort works on 1.20.1" is a test, not a claim.
5. **Differential traces** — same effect program, all interpreters, divergence vs declared deficits.
6. **Manual feel checklist** — the one thing that stays human. Sound/particle taste is authored, not emergent.

**Correctness definition:** a port is correct when its behaviour differs from the reference only in *declared* ways, and the system proves the declarations.

---

## 16. Failure modes

- **Thin corpus kills the flywheel.** With one mod, nothing compounds. Seed the delta registry by hand for the first hops — as an asset, not a side effect.
- **Wrong primitives are the fixed point.** Errors propagate into the whole corpus. Highest-stakes decision; design slowly.
- **Rendering variance stays hard.** Emergence makes churn visible and shared; it does not remove it.
- **Data gravity or nothing.** If deltas/ledger/rules live in prose, nothing compounds. Machine-readable, versioned, from day one.
- **Seam creep.** The Architectury trap: seams are verbs, not frameworks. Enforce the budget.
- **Agent trust without gates.** Agent work is only as good as its deterministic verification. Never merge ungated agent output.
- **The machine is a second product.** Its justification is amortization: one machine, N mods, M targets.

---

## 17. Roadmap: minimum viable flywheel

Seed in this order; each step proves one load-bearing idea:

1. **Primitive vocabulary v0 + effect IR spike** on a storage operation with interaction and feedback. Proves the tower; validates the 8 verbs.
2. **Delta registry v0**, hand-seeded with the 1.21.1 → 26.x hop for the Flow primitive. Proves the compounding unit.
3. **Differential harness v0**: one effect trace against Fabric and NeoForge-modern interpreters, traces compared. Proves semantic equivalence testing.
4. **Seam auditor v0**: script; run on Osmosis + one other mod. Proves the adoption path.
5. **Substrate bake-off**: 3 targets (1.21.1-Fabric, 26.2-Fabric, 26.2-NeoForge) on Unimined vs Stonecutter + Modstitch.
6. **Verification ladder**: one unit test, one server gametest, one client gametest against Osmosis.

Once 1–3 exist, every subsequent target, version, and mod gets cheaper. That is the emergence: stop building ports; start accumulating a corpus that ports for you.

---

## 18. Open decisions

- **Name.** Mycelium is provisional; Kiln and Grain were alternates.
- **Substrate.** Unimined (widest coverage incl. legacy + total conversions) vs Stonecutter + Modstitch (modern-native, cleaner ceremony). Spike both.
- **Delta format.** Custom (recommended) vs adapting an existing ruleset format (ModPorter-style).
- **Spec format.** YAML/TOML vs JSON; must be machine-readable and diffable.
- **Where the corpus lives.** Machine repo (recommended) vs per-mod repos with a shared registry.
- **Licence and contribution model.** Machine licence separate from mod licences; design for eventual external target contributions.
- **Primitive count.** 8 is the hypothesis. Validate against Osmosis + one content mod + one client mod before freezing.

---

## Appendix A — Consumer source audit (redacted)

Consumer source inventories, implementation findings, and audit commands are omitted from this publication edition.

## Appendix B — Glossary

- **Primitive** — one of ~8 semantic verbs targets implement (`Observe`, `Query`, `Mutate`, `Register`, `Persist`, `Send`, `Receive`, `Present`).
- **Seam** — derived library boundary (Flow, Interaction, Presentation, …) implemented over primitives.
- **Effect IR** — the reified stream the core emits; interpreters per target.
- **Delta** — a version-hop transformation module; the compounding unit of the corpus.
- **Capability manifest** — per-target declaration of supported features and floors.
- **Deficit** — a declared, proven behavioural difference between targets.
- **PSA (Port Surface Area)** — diff size per version hop across primitives, manifests, and resources; the system's health metric.
- **Lazy target** — a target built on demand rather than prebuilt.
