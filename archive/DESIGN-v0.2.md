> Publication edition: consumer source inventories, implementation findings, and audit commands are redacted. The architectural record is retained; complete source-audit records remain private.

# Mycelium — Founding Design Document

**A mod development machine: one intent, many substrates, explicit evidence.**

| | |
|---|---|
| Status | Draft v0.2 — integrates external red-team review (2026-09-20) and read-only source verification |
| Supersedes | v0.1 (archived at `archive/DESIGN-v0.1.md`) |
| Date | 2026-09-20 |
| Working name | Mycelium (provisional) |
| First reference implementation | Osmosis; private source-audit details omitted |
| Scope | Develop and maintain Minecraft mods across viable substrates; start with Osmosis, scale to any mod or idea at any depth |
| Claims discipline | No compilation or game execution was performed for this document. Consumer-specific code findings and citations are redacted in this edition. Ecosystem facts are time-bound. |

---

## 0. Thesis

Mycelium is an extensible development-time compiler, adaptation system, and evidence registry for Minecraft mods. It preserves authored intent, factors recurring platform differences into **versioned semantic contracts**, and produces native target artifacts with explicit capability and verification manifests.

**Its guarantee is not that every feature translates automatically.** Its guarantee is that supported contracts, native work, unresolved work, behavioral differences, and evidence are represented explicitly rather than concealed.

The compounding unit is a **verified contract implementation or transformation with applicability conditions and regression evidence** — not an unqualified source-code diff, and not a fixed primitive count.

**Ambition is universal reach. Cheapness is earned and measured, never assumed.**

---

## 1. Goals and boundaries

**Goals** (preserved from v0.1): Osmosis first; independent mod repositories; small runtime artifacts; agent-operable tooling; adopted build infrastructure; a reusable corpus.

**Three ambitions, separated:**

- **Reach** — a target provider or native extension can represent genuinely target-specific work without redesigning the machine.
- **Automation** — known contracts and recipes cover an increasing proportion of that work.
- **Assurance** — an artifact is advertised only at the evidence level actually achieved for its exact environment.

**Boundaries:**

- "Every loader and version" denotes an extensible set of **viable substrate combinations**, not a Cartesian product. Creating a missing substrate is separate engineering, represented and costed as such.
- Mods need not become pure portable cores to receive value. Adoption is incremental (see §13).
- No general-purpose modding DSL. No magic for rendering/UI churn. No framework tax on users.

---

## 2. Evidence and hypotheses

### 2.1 Initial consumer evidence (redacted)

The first consumer exposed a small, enumerable platform surface. Its source inventory and implementation findings are omitted from this publication edition.

### 2.2 General lesson

> **Two ports can perfectly agree while preserving the same reference defect.**

The source-specific findings supporting this lesson remain in the private audit record.

### 2.3 What this does not prove

Osmosis is unusually portable. It validates the *first product*, not the universality of the architecture. Hypotheses still to test:

- Contract backends can be reused across unrelated mods.
- Typed planning can isolate most of Osmosis's behavior.
- Explicit deltas reduce repeated porting work.
- Differential testing plus independent invariants identifies meaningful divergences.

None is established by building two artifacts. Gate D and Gate E (§14) exist to falsify them.

---

## 3. Ontology: what a mod is

A mod is a bundle of **effects against a shared mutable world**, expressed through extension points.

- **Intent (invariant-ish):** content definitions, rules (`event ∧ condition → action`), presentation style, persistence semantics, balance numbers.
- **Expression (variant):** metadata, registration, resources, behavior hooks, state storage, protocol, presentation, commands, config.

Variance axes — loader, MC version, resource format, Java version, build tooling — are **useful classifications, not an exhaustive target identity**. Exact identity is an environment profile (§9).

Intent is not eternally fixed either: each feature contract carries a version and a migration policy.

---

## 4. Strategy taxonomy

Choose per feature, not per project:

1. **Interpret** — intent into data/resources + tiny stable shim. Content mods are ~90% this.
2. **Adapt** — portable domain logic + per-target contract backends.
3. **Translate** — ship one target; compatibility layers (Connector/FFAPI, ReForged, Porting Lib) translate. Bonus channel, never foundation.
4. **Vendor** — adopt an existing shim for a subsystem.

Plus a **first-class third path** alongside 1–3:

5. **Native extension** — for platform specificity that is intrinsic: specialized rendering, world generation, mixins, bytecode hooks, unusual protocols, another mod's integration. Native extensions declare target selectors, required contracts, platform dependencies, lifecycle/thread requirements, tests, performance budgets, and an owner. They participate in the same build, evidence, and release machinery.

Native extensions are not a loophole that counts manual work as automatic portability. **Track native work separately; promote a repeated native pattern into a shared contract only when independent consumers demonstrate stable common semantics.** This prevents both a lowest-common-denominator API and an ever-growing universal Minecraft wrapper.

---

## 5. Architecture: the revised tower

| Layer | Responsibility |
|---|---|
| L0 — Intent package | Authored behavior, examples, compatibility promises, safety invariants, feel criteria; stable feature identifiers connect prose, implementation, tests, manifests, deficits |
| L1 — Semantic kernels | Pure algorithms and state machines over explicit inputs; no live Minecraft or loader objects |
| L2 — Contract families | Typed, versioned domain capabilities and their laws |
| L3 — Seams and planning | Reusable domain algorithms, effect planning, capability-aware feature composition |
| L4 — Target providers | Native implementations, lifecycle binding, resources, metadata, era-specific code generation |
| L5 — Machine | Dependency resolution, audit, recipe planning, generation, builds, evidence, publication |
| L6 — Verification | Independent contract suites, model tests, real-game observations, compatibility profiles, performance and feel |

Each layer may contain generated, authored, and versioned native code. Generated output identifies its inputs and recipe versions. **Mod repositories pin machine, contracts, corpus, and target-provider versions independently.**

The portable core has an explicit platform-independence check. A separate adopted-native layer may retain Minecraft imports — do not call that layer pure merely because loader imports disappeared.

---

## 6. Effect categories and contract families

`Observe`, `Query`, `Mutate`, `Register`, `Persist`, `Send`, `Receive`, `Present` remain useful **effect categories for auditing and documentation**. They are a taxonomy, not an architecture: eight categories do not bound the operations, data models, temporal constraints, or laws inside them. A target implements the **specific contracts** its features demand — not eight universal functions.

**The reusable unit is a typed, versioned domain contract.** Candidate names (proposals, not APIs):

```text
inventory.enumerate@1     inventory.transfer@1     inventory.reorder@1
interaction.use-block@1   presentation.particle-cue@1   persistence.player-state@1
```

Each contract specifies more than signatures: allowed inputs, observable results, failure behavior, side/lifecycle restrictions, and properties every implementation must satisfy. For `inventory.transfer`: item identity preservation, bounded quantities, access restrictions, partial-acceptance semantics. For `inventory.reorder`: legal slot assignments, and whether reordering is meaningful for that storage at all.

**Corrected scaling claim:**

> A new feature requires no new target implementation when its required contracts are already covered. It still requires feature-level verification.

A shared backend is amortized over consumers of those contracts.

---

## 7. Execution staging and receipts

An executable system needs results, branches, failures, and lifecycle boundaries — not a flat list of fire-and-forget commands. Start with a **typed planning boundary**:

```text
native event + execution context
    -> inspect / snapshot within a supported lifetime
    -> pure policy and planning
    -> target-native validation and execution
    -> committed outcome or explicit failure
    -> presentation and synchronization from that outcome
```

**Outcomes:** `Committed(receipt)`, `Stale`, `Rejected`, `Unsupported`. A receipt describes what actually committed — not what the planner hoped to move. Feedback (sound, particles, sync) consumes the receipt. A successful-looking `Send(visual)` must never be justified merely because a `Mutate` instruction appeared earlier.

**Separate phases.** Registration/resource generation, runtime behavior, persistence migration, and presentation plans can share infrastructure without sharing one execution model. This is not academic: in 26.1, creating `ItemStack` requires loaded registries (`ItemStackTemplate` serves earlier uses) — a lifecycle distinction the abstraction must represent.

**No premature IR.** Pure typed functions and small plans are enough to prove the first seam. A general effect language, if ever justified, must emerge from repeated requirements and carry explicit results, control flow, errors, lifetimes, and scheduling.

**Performance discipline.** Use interpreters for tests and diagnosis; allow generated or direct target-native execution for hot paths. Do not allocate an effect object per render tick for architectural symmetry. Benchmark before replacing clear code with machinery.

---

## 8. Flow: the first demanding contract

### 8.1 Traits, not booleans

Flow distinguishes item identity, aggregate transfer, slot layout, transaction participation, container ownership, and access rules. Capabilities are **traits**, not `Mutate=true`:

- stable slot addressing
- complete enumeration
- trustworthy simulation
- rollback participation
- preservation of item identity
- support for slot reordering

Applicability can depend on the **runtime container**, not only the loader version. Target-level capability and runtime applicability checks are both required.

### 8.2 Honest transactions

Do not equate API transactions with database durability or global isolation. Document participant and lifetime boundaries. A notification failure after commit is not permission to reverse or repeat a mutation blindly.

**Legacy handlers:** NeoForge's own migration guidance states that arbitrary legacy `IItemHandler` implementations cannot be universally wrapped into the new transactional interface; Fabric's wrapper documentation likewise states rollback assumptions. For legacy or opaque providers, use reviewed provider-specific adapters or a separately named **weaker contract**. Do not fabricate rollback through generic snapshot/restore. When required guarantees cannot be established, reject the operation on that provider or require an explicitly authorized alternative.

### 8.3 Laws

Author explicit laws: conservation where promised, count bounds, identity preservation, sided access, rejected-operation behavior, stale-plan handling, correspondence between committed quantity and feedback. Sorting adds layout legality and idempotence under a fixed ordering policy.

### 8.4 The retained counterexample

Destination holds 10 matching items; move reports accepting 5 more; destination still exposes 10. The current zero-count predicate does not identify the loss. Build the fixture first, author the conservation contract, then choose the supported guarantee per provider. A simple before/after count is not a universal repair: transformed resources, incomplete views, aliases, and side effects need a defined provider contract.

### 8.5 Sorter extraction boundaries

1. **Item classification and identity policy** — what counts as the same item, the sorting key, what name ordering means.
2. **Pure layout planning** — given explicit items and slot constraints, the resulting arrangement.
3. **Native validation and application** — is the plan still valid, can this provider apply it with the promised guarantees.

Substantially more useful than renaming the class into a generic `Mutate` interpreter.

---

## 9. Target profiles and support truth

A **target profile** resolves exact game, loader, mappings/namespace, API and library dependencies, toolchain, and artifact hashes. Runtime evidence additionally records client/server topology, feature flags, relevant config, resource/datapack state, tested integration set, and rendering environment where material. Knowing which combination a support claim describes is required; testing every combination is not.

**Four separate fields:**

| Field | Example values |
|---|---|
| Feasibility | satisfiable, unsatisfiable, unresolved |
| Implementation | native, adapted, translated, pending |
| Evidence | compile, boot, contract, integration, performance, feel — each pass/fail/unknown |
| Release policy | experimental, supported, blocked, retired |

- A solver establishes satisfiability of declared constraints, not runtime correctness. Return reasons for unsatisfied requirements.
- **Required capabilities fail resolution when missing; only optional features may be pruned** under their declared policy. The machine must never silently redefine the product until it becomes buildable.
- **Availability semantics:** class presence, enabled gameplay, obtainability, and compatible behavior are different facts and must not collapse into one boolean. (v0.1's `bundle >=1.17` floor was imprecise: the released bundle feature arrived in 1.21.2 and changed prototype controls.)

---

## 10. Deltas and the corpus

**Deltas are directed, guarded, versioned transformations** — not automatically bidirectional.

Every reusable recipe records: identity and version; source and destination conditions; semantic intent; implementation; dependencies; conflicts and ordering; preconditions; postconditions; known losses; affected contracts; positive and negative fixtures; provenance and licensing; owner; and evidence against exact hashes.

**Namespace ≠ version.** Yarn's `CustomPayload` and the official `CustomPacketPayload` name the same 1.21.1-era concept under different namespaces; v0.1's illustrative "delta" conflated them. Namespace must be explicit in the model. Real version-hop deltas look like the 1.20.4→1.20.5 networking rewrite or the 1.21.11→26.1 era jump (Java 25, deobfuscated jar, Loom rename).

**Reverse recipes are separate, conditional, tested assets.** If two distinguishable newer states collapse into one older state, an inverse cannot recover the distinction without retained information. A reverse may be exact, conditional, lossy, dependent on retained data, or unsupported. That is still a powerful backporting system — an honest one.

**Prefer composing target models and rendering from known inputs** over stacking textual patches. Where rewrites are necessary: structured matches, exact applicability guards, ordering-conflict tests, path-independence tests where claimed.

**Refusals are scoped evidence, not eternal truth.** Store input fingerprint, reason, and invalidation trigger. New upstream releases reopen decisions; transient fetch failures do not become semantic impossibility records.

**The corpus must support invalidation.** A defective shared recipe can spread one mistake across many mods. The registry identifies dependent artifacts, revokes stale claims, and triggers rebuilding or relabeling.

> **The valuable asset is not a large pile of deltas. It is a trustworthy collection of applicable deltas.**

---

## 11. Verification and the oracle boundary

**Three independent evidence sources:**

1. **Authored contract properties** — intended behavior: conservation where promised, legal slots, correct rejection, identity preservation.
2. **Reference characterization** — existing behavior, including known defects and deliberate compatibility decisions. Defects get explicit decisions: preserve for compatibility, fix everywhere, or deprecate. Never silently blessed through differential agreement.
3. **Real-target observations** — what the built artifact actually does: real hooks firing, world-state transitions, protocol outcomes, save/reload behavior, packaged artifacts.

None replaces the others.

**Compare observations, not self-reported effects.** Two interpreters can emit identical traces while a callback never fires in production or the actual mutation differs from the logged receipt. Normalize only explicitly irrelevant variation; keep causally important ordering. A trace comparator must not erase item loss, missing permission checks, or failed persistence to make targets look equivalent.

**Scoped assurance statement:**

```text
Artifact A satisfied contract C under test suite S and environment E,
with approved differences D and unresolved obligations U.
```

This is evidence, not proof over every game state and modpack. Reserve proof terminology for mechanically established properties under stated assumptions.

**The harness must prove it can reject bad work.** Deliberately incorrect implementations — wrong quantity, ignored slot restriction, lost metadata, feedback before commit, missing persistence — must be rejected for the intended reason. This is a better first milestone than generating many tests from the same manifest that declares the features: a generator can expand fixtures; it cannot derive the meaning of "correct sorting" from `sort: true`.

**Deficits** specify affected feature, exact target conditions, observable predicate, user impact, approved bound, regression fixture, owner, and invalidation policy. A particle substitution can be an approved fidelity difference; unexplained item loss cannot claim successful support of a conservation contract.

**Replay levels** (do not conflate): pure planner replay from recorded inputs; backend replay against a controlled fixture; real-game reproduction with matching dependencies and environment. Production diagnostics are bounded and opt-in.

**Evidence bundle** binds source, recipes, test definitions, reference version, environment, declared deficits, reports, and artifact digest. Changes invalidate affected claims.

**Verification ladder:** pure unit and property tests; adapter-contract fixtures; packaged-artifact boot tests; dedicated-server and client tests; multi-client protocol tests; save/reload and migration fixtures; selected integration profiles; performance comparisons; authored feel checks. Adopt Fabric's server/client game-test infrastructure and production-run support; NeoForge's `runGameTestServer` exit code as CI gate. Distinguish gameplay failures from infrastructure failures and inconclusive results. Record flaky evidence honestly; retrying until green is not a support policy.

---

## 12. Agent operation and supply-chain boundaries

- Start with a **deterministic workflow orchestrator**; add parallel agents when independent work exists.
- Agents **propose** implementations and recipe changes. **Protected policies control acceptance tests, approved deficits, and publication.** A repair must not authorize itself by weakening its own gate.
- Run untrusted builds and transformations in **isolated, disposable environments without release credentials**. Resolve and verify dependencies; separate artifact verification from publication; pin corpus versions; record provenance.
- Track licenses and origins at recipe, copied-code, and generated-artifact boundaries. A machine's license does not change the obligations of incorporated code.
- Staged governance: quotas, scheduling, and broader supply-chain controls arrive when measured bottlenecks justify them — designed now, enforced later.

---

## 13. Economics and adoption

**Target cost model:**

```text
new contracts + target-native residual work + cross-contract integration
+ verification + distribution + ongoing maintenance
```

Reused contracts reduce some terms. They do not abolish all terms.

**Measure** (not assume): human effort per supported target; unseen-target completion; unchanged recipe reuse across unrelated mods; residual target logic; regression escape rate; runtime overhead; compute/storage cost; maintenance after upstream changes.

**Port Surface Area is a diagnostic, not the objective.** A one-line workaround can be semantically dangerous; a longer explicit adapter can be correct and cheap. A target line budget triggers **explanation**, not mandatory abstraction — and complexity moved into helpers or generators counts, rather than being rewarded for concealment.

**Demand-driven builds** defer compute and distribution for known recipes. An unknown target initiates resolution and possibly engineering — not a promise of instant synthesis. Apply honest experimental/support labels.

**Economic hypothesis:** human work per supported target decreases within covered semantic families, and shared-contract investments repay their engineering and maintenance cost across independent consumers. Measured on held-out targets and unrelated mods — not asserted.

**Incremental adoption path:**

> Manage existing native projects → extract useful seams → reuse proven contracts → automate recurring transformations.

Each stage justifies itself. No mod is required to buy the entire abstraction upfront.

---

## 14. Roadmap: gates that could disprove the architecture

| Gate | Work | Must demonstrate |
|---|---|---|
| **A — Trustworthy baseline** | Pin the actual 1.21 source and tool artifacts; characterize behavior; add independent Flow invariants; add a deliberately incorrect implementation | The harness rejects the bad implementation for the intended reason; reference defects distinguished from port defects |
| **B — Separate the axes** | Small loader × era matrix: reference-era Fabric + same-era NeoForge; modern Fabric + modern NeoForge; pre-component legacy as next stress case. Preflight artifact availability | Loader adaptation and version migration are separately understood; tool substrate viability proven before a large plugin suite |
| **C — One vertical slice** | Extract one Flow operation: typed inputs, native executor, receipt, success presentation, failure behavior. Keep the rest of Osmosis native | A shipped-quality useful slice — not a universal IR prototype without a working mod |
| **D — Cross-mod reuse + depth probes** | Reuse the same versioned contract/backend in an unrelated consumer without copying or Osmosis-specific generalization; add content/persistence, rendering/input, and lifecycle/mixin probes | Independent reuse demonstrated; residual work measured; native-extension path documented |
| **E — Held-out generalization** | Freeze contracts and corpus; attempt an unseen target or consumer; compare against a comparable manual baseline | Actual reuse, new engineering, failures, and cost recorded honestly. Newly authored work is not called "automatic reuse" |
| **F — Controlled expansion** | Caching, release automation, scheduling, auditing, agent parallelism — as measured bottlenecks justify them | Expansion follows evidence, not ambition |

A gate failure **revises the abstraction or contract** — it does not automatically kill the project. Failure to generalize one interface teaches where the boundary belongs.

**Substrate:** keep build-tool providers replaceable; choose the first by a representative build-and-test spike, not the longest advertised loader list. Separate the machine's JVM from artifact runtimes; choose an explicit legacy strategy (lower-language-level shared kernel, source generation, or per-era modules sharing contracts — sealed interfaces are Java 17+).

---

## 15. Decisions locked / open questions

**Locked for now**

- Domain contracts over a frozen eight-method runtime.
- Ordinary typed code and small plans before any general-purpose IR or DSL.
- Native extensions and incremental adoption are first-class.
- Exact lockfiles; directed, guarded recipes.
- Independent correctness tests and support policy are protected from repair authority.
- Flow is the first demanding contract; validate it before building the machine.
- Build-tool providers replaceable; semantics owned by the machine.

**Open**

- Name (Mycelium provisional; Kiln, Grain alternates).
- First substrate (Unimined vs Stonecutter + Modstitch) — decided by Gate B spike.
- Contract format and corpus storage (machine repo vs shared registry).
- Java-era strategy for legacy targets.
- Governance enforcement timeline.

---

## Appendix A — Consumer source audit (redacted)

Consumer source inventories, implementation findings, and audit commands are omitted from this publication edition.

## Appendix B — Glossary

- **Contract** — typed, versioned domain capability with laws; the reusable unit.
- **Effect category** — one of eight audit classifications (Observe, Query, Mutate, Register, Persist, Send, Receive, Present); taxonomy, not architecture.
- **Trait** — a capability property (rollback participation, stable slots, …); applies per provider and runtime container.
- **Receipt** — record of what actually committed; drives feedback and sync.
- **Delta / recipe** — directed, guarded, versioned transformation with evidence.
- **Deficit** — declared, approved, fixture-backed behavioral difference; never a wildcard.
- **Target profile** — exact resolved environment description; the identity of a support claim.
- **PSA** — Port Surface Area; diagnostic metric, not an objective.
- **Evidence bundle** — the artifact-bound record of claims, tests, environment, deficits, digests.

## Appendix C — Changelog: v0.1 → v0.2

Integrated from external red-team review (2026-09-20), with source verification:


## Appendix D — Grounding

- Delta-oriented programming: DeltaJ/DeltaJava (Schaefer et al.) — product = core + deltas.
- Feature-model analysis: Mendonça/Wąsowski/Czarnecki, "SAT-based analysis of feature models is easy" (SPLC 2009).
- Bidirectional transformations / lenses: round-trip discipline as tests.
- Algebraic effects / free monads: sealed interfaces + interpreters (shape, not runtime).
- Differential testing: trace equivalence, divergence baselines.
- Demand-driven build systems: Mokhov et al.; caching + constructive traces.
- Ecosystem: LuckPerms (api/common/platforms), ViaVersion (api/common/platforms/universal), Unimined, Stonecutter/Modstitch, Sinytra Connector + FFAPI, Forgix, Fabric/NeoForge game-test infrastructure, NeoForge Transfer Rework (21.9.1-beta).
