> Publication edition: consumer source inventories, implementation findings, and audit commands are redacted. The architectural record is retained; complete source-audit records remain private.

# Mycelium — Founding Design Document

**A mod development machine: one intent, many substrates, explicit evidence.**

| | |
|---|---|
| Status | Draft v0.3 — integrates second red-team review (85/100) with primary-source verification |
| Supersedes | v0.2 (archived at `archive/DESIGN-v0.2.md`); v0.1 also archived |
| Date | 2026-09-20 |
| Working name | Mycelium (provisional) |
| First reference implementation | Osmosis; private source-audit details omitted |
| Scope | Develop and maintain Minecraft mods across viable substrates; start with Osmosis, scale to any mod or idea at any depth |
| Claims discipline | No compilation or game execution was performed. Consumer-specific code findings and citations are redacted in this edition. API claims verified against primary sources on 2026-09-20 (Appendix D). Ecosystem facts are time-bound. |

---

## 0. Thesis

Mycelium is an extensible development-time compiler, adaptation system, and evidence registry for Minecraft mods. It preserves authored intent, factors recurring platform differences into **versioned semantic contracts**, and produces native target artifacts with explicit capability and verification manifests.

**Its guarantee is not that every feature translates automatically.** Its guarantee is that supported contracts, native work, unresolved work, behavioral differences, and evidence are represented explicitly rather than concealed.

**Safety is not satisfaction.** An implementation that violates nothing but does nothing useful still fails its contract. Contracts carry **positive behavioral obligations** alongside safety properties (§8.3).

**No externally successful receipt exists before the operation's promised commit boundary has succeeded.** Provisional, nested, and parent-scoped work is represented as provisional (§7).

The compounding unit is a **verified contract implementation or transformation with applicability conditions and regression evidence** — not an unqualified diff, not a fixed primitive count.

**Ambition is universal reach. Cheapness is earned and measured, never assumed.**

---

## 1. Goals and boundaries

**Goals:** Osmosis first; independent mod repositories; small runtime artifacts; agent-operable tooling; adopted build infrastructure; a reusable corpus.

**Three ambitions, separated:**

- **Reach** — a target provider or native extension can represent genuinely target-specific work without redesigning the machine.
- **Automation** — known contracts and recipes cover an increasing proportion of that work.
- **Assurance** — an artifact is advertised only at the evidence level actually achieved for its exact environment.

**Boundaries:**

- "Every loader and version" denotes an extensible set of **viable substrate combinations**, not a Cartesian product. Creating a missing substrate is separate engineering, represented and costed as such.
- Mods need not become pure portable cores to receive value. Adoption is incremental (§13).
- No general-purpose modding DSL.
- **"No framework tax" is bounded, not absolute.** Target artifacts carry no runtime library dependency beyond vanilla + loader in the first runtime model (§12); artifact size, startup, and runtime overhead are measured against a native baseline; development burden is tracked like any other cost.
- **Portability claims are separated:** source-code portability does not imply save-file portability or cross-target protocol compatibility (§8.6).

---

## 2. Evidence and hypotheses

### 2.1 Initial consumer evidence (redacted)

The first consumer exposed a small, enumerable platform surface. Its source inventory and implementation findings are omitted from this publication edition.

### 2.2 General lesson

> **Two ports can perfectly agree while preserving the same reference defect.**

The source-specific findings supporting this lesson remain in the private audit record.

### 2.3 What this does not prove

Osmosis is unusually portable. It validates the first product, not the architecture's universality. Hypotheses to falsify in Gates D and E: contract backends reuse across unrelated mods; typed planning isolating behavior; deltas reducing repeated work; differential testing plus independent invariants finding meaningful divergences.

---

## 3. Ontology: what a mod is

A mod is a bundle of **effects against a shared mutable world**, expressed through extension points.

- **Intent (invariant-ish):** content definitions, rules (`event ∧ condition → action`), presentation style, persistence semantics, balance numbers.
- **Expression (variant):** metadata, registration, resources, behavior hooks, state storage, protocol, presentation, commands, config.

Variance axes — loader, MC version, resource format, Java version, build tooling — are **classifications, not an exhaustive target identity**. Exact identity is an environment profile (§9). Intent is versioned too: each feature contract carries a version and migration policy.

---

## 4. Strategy taxonomy (orthogonal attributes, not buckets)

Each feature is described by a set of independent attributes, not forced into one exclusive category:

**Realization approach**

1. **Generated content** — declarative resources; the generator does the work.
2. **Portable logic** — algorithms and behaviors whose semantics are shared; contracts and typed planning.
3. **Translated** — ship one target; compatibility layers (Connector/FFAPI, ReForged, Porting Lib) translate. Bonus channel, never foundation.
4. **Native extension** — intrinsic platform specificity: specialized rendering, world generation, mixins, bytecode hooks, unusual protocols, another mod's integration.

**Sourcing** — authored / vendored / adopted.

**Locality** — core / provider / extension.

A feature might be `{portable logic, authored, core}` or `{native extension, authored, extension}` or `{generated content, authored}`. A native extension declares target selectors, required contracts, platform dependencies, lifecycle/thread requirements, tests, performance budgets, and an owner; it participates in the same build, evidence, and release machinery. **Track native work separately; promote a repeated native pattern into a shared contract only when independent consumers demonstrate stable common semantics.**

---

## 5. Architecture: the tower

| Layer | Responsibility |
|---|---|
| L0 — Intent package | Authored behavior, examples, compatibility promises, safety invariants, feel criteria; stable feature identifiers connect prose, implementation, tests, manifests, deficits |
| L1 — Semantic kernels | Pure algorithms and state machines over explicit inputs; **no live Minecraft or loader objects** |
| L2 — Contract families | Typed, versioned domain capabilities and their laws; platform-free |
| L3 — Seams and planning | Reusable domain algorithms, effect planning, capability-aware composition; references platform types only through L2 contracts |
| L4 — Target providers | Native implementations, lifecycle binding, resources, metadata, era-specific generation; **the designated platform-access layer** |
| L5 — Machine | Dependency resolution, audit, recipe planning, generation, builds, evidence, publication |
| L6 — Verification | Independent contract suites, model tests, real-game observations, compatibility profiles, performance and feel |

**Provenance (authored/generated) is orthogonal to platform dependence.** Native platform access is restricted to L4 and native extensions. Generated output identifies its inputs and recipe versions. Mod repositories pin machine, contracts, corpus, and target-provider versions independently.

---

## 6. Effect categories and contract families

`Observe`, `Query`, `Mutate`, `Register`, `Persist`, `Send`, `Receive`, `Present` are **audit and documentation categories**. They are a taxonomy, not an architecture: eight categories do not bound the operations, data models, temporal constraints, or laws inside them. A target implements the **specific contracts** its features demand.

**The reusable unit is a typed, versioned domain contract.** Candidate names (proposals):

```text
inventory.enumerate@1     inventory.transfer@1     inventory.reorder@1
interaction.use-block@1   presentation.particle-cue@1   persistence.player-state@1
```

Each contract specifies allowed inputs, observable results, failure behavior, side/lifecycle restrictions, **safety properties**, and **positive behavioral obligations** (§8.3).

**Corrected scaling claim:**

> A feature requires no new target implementation when its required contracts, **lifecycle bindings, and composition requirements** are already covered. It still requires feature-level verification.

**Composition is a first-class requirement.** Two correctly implemented operations can compose incorrectly: withdrawing payment and delivering an item can each work perfectly while failing all-or-nothing when they use separate transaction boundaries. Coverage that ignores composition is not coverage.

---

## 7. Execution staging, transaction ownership, and receipts

```text
native event + execution context
    -> inspect / snapshot within a supported lifetime
    -> pure policy and planning
    -> target-native validation and execution
    -> committed outcome or explicit failure
    -> presentation and synchronization from that outcome
```

**Transaction ownership is part of the contract.** An executor must either own the final transaction boundary or participate in an explicitly supplied parent context. Work accepted into a parent transaction is **provisional until that parent succeeds**.

**Finality rule:**

> **No externally successful receipt exists before the operation's promised commit boundary has succeeded.**

This is verified API reality, not paranoia: Fabric's `Transaction` javadoc states that committing a nested transaction validates changes *"but they may still be cancelled later if a parent transaction is cancelled,"* and that side effects such as `markDirty()` or neighbor updates *"should be deferred until after the outer transaction is closed."* NeoForge's rework has the same parent/commit structure.

**Outcomes:** `Committed(receipt)` (final boundary succeeded), `Stale`, `Rejected`, `Unsupported`. A **provisional or deferred receipt** covers parent-scoped work internally; a pending internal result is sufficient — no new public outcome type is required. An exception that leaves the outcome unconfirmed **must not become an ordinary `Rejected`**, because that could invite an unsafe retry. Unconfirmed/indeterminate is its own state.

A receipt describes what actually committed — not what the planner hoped to move. Feedback (sound, particles, sync) consumes the **final** receipt.

**Separate phases.** Registration/resource generation, runtime behavior, persistence migration, and presentation plans share infrastructure, not one execution model. In 26.1, creating `ItemStack` requires loaded registries (`ItemStackTemplate` serves earlier uses) — a lifecycle distinction the abstraction must represent.

**No premature IR.** Pure typed functions and small plans prove the first seam. A general effect language, if ever justified, must emerge from repeated requirements and carry explicit results, control flow, errors, lifetimes, and scheduling.

**Performance discipline.** Interpreters for tests and diagnosis; generated or direct native execution for hot paths. No effect object per render tick for symmetry. Benchmark before replacing clear code with machinery.

---

## 8. Flow: the first demanding contract

### 8.1 Traits: meaning, basis, applicability

Capabilities are **traits**, not `Mutate=true`: stable slot addressing; complete enumeration; trustworthy simulation; rollback participation; identity preservation; reorder support.

A trait has three separate things:

- **Meaning** — what the trait asserts under the contract.
- **Basis** — why we believe the provider has it. Bases, from strongest to weakest: a **reviewed adapter** qualified for a specific implementation + dependency version; **provider self-declaration**; **fixture observation**; none.
- **Current applicability** — a bounded runtime check that the live object and access context still match the adapter's conditions.

**No silent upgrades between bases.** A self-declaration is not a reviewed adapter. A fixture observation is not a guarantee for all containers. The policy answers: *what evidence is sufficient to use this provider under this contract, and what happens when that evidence is absent?* Absent evidence → weaker named contract, explicit rejection, or `Unsupported` — never experimental mutation of arbitrary live inventories.

**Unknown, unsupported, and known-incompatible are different states.** Preserve the distinction.

Verified basis for skepticism:

- Fabric `Storage.supportsInsertion()` returns *"true otherwise or in doubt"* — true is not a promise of success.
- Fabric's wrapper guidance: `ItemStorage.of` assumes slots are independent; otherwise *"there could be side effects that get left behind when a transaction is reverted."*
- NeoForge's transfer API includes `VoidingResourceHandler` — *"resource handler that voids any inserted resource."* Intentional destruction is supported behavior; transaction participation does not imply conservation.

### 8.2 Honest transactions

Do not equate API transactions with database durability or global isolation. Document participant and lifetime boundaries; nested work is provisional (§7). A notification failure after commit is not permission to reverse or repeat a mutation blindly.

**Legacy handlers:** NeoForge states plainly that *"it is not possible to provide a wrapper that turns any IItemHandler into an ResourceHandler"* due to transactional semantics (the reverse wrapper is provided). For legacy or opaque providers: reviewed provider-specific adapters or a separately named **weaker contract**. Do not fabricate rollback through generic snapshot/restore. When required guarantees cannot be established, reject the operation on that provider or require explicitly authorized alternative behavior.

### 8.3 Laws and positive obligations

**Safety properties:** conservation where promised, count bounds, identity preservation, sided access, rejected-operation behavior, stale-plan handling, correspondence between committed quantity and feedback. Sorting adds layout legality and idempotence.

**Safety is satisfiable by doing nothing.** A transfer that always moves zero can preserve every item; a sorter that returns the original is idempotent. Positive behavioral obligations close this:

- For a **simple, single-variant transfer between stable, disjoint, ordinary stores**, with fixture assumptions stated:

  ```text
  expected moved = min(requested amount, available amount, admissible capacity)
  ```

  Under those stated assumptions, returning less is **incorrect**, not conservative.

- For **sorting**: the resulting layout must satisfy the declared ordering policy over the permitted slots. Define whether failure to find a legal arrangement is acceptable when a valid arrangement exists — an unnecessarily rejecting planner must not pass as "safe."

- **`NoOpBackend` is a required member of the deliberately incorrect implementations.** The harness must reject it for failing positive behavior, not merely accept it for violating nothing.

**"Does not damage the world" and "implements the feature" are separate obligations.**

### 8.4 The retained counterexample

Destination holds 10 matching items; a move reports accepting 5 more; destination still exposes 10. The zero-count predicate does not identify the loss. Build the fixture first, author the conservation contract, then qualify providers. A simple before/after count is not a universal repair: transformed resources, incomplete views, aliases, and side effects need a defined provider contract.

### 8.5 Sorter extraction boundaries

1. **Item classification and identity policy** — what counts as the same item, the sorting key, what name ordering means.
2. **Pure layout planning** — given explicit items and slot constraints, the resulting arrangement.
3. **Native validation and application** — is the plan still valid, can this provider apply it with the promised guarantees.

### 8.6 Item identity is foundational, not policy detail

Three notions, never collapsed into one universal serializer:

- **Target-local identity** — whether native items can be combined or substituted without losing meaningful state.
- **Policy descriptors** — what the portable algorithm actually needs: category, ordering key, protected status, authored inputs.
- **Cross-target correspondence** — which differences are acceptable when comparing behavior across targets.

**For the first Flow slice:** immutable **opaque identity tokens** scoped to the operation; the backend retains full native item information; the pure planner preserves state it does not understand. Conservation tests must not pass while resulting items are wrong.

**Portability claims separated, explicitly:** source-code portability does **not** imply save-file portability or cross-target protocol compatibility. Saved state and wire protocols carry their own schema versions, independent of contract versions, so a contract version never becomes a promise to convert entire worlds.

---

## 9. Target profiles and support truth

A **target profile** resolves exact game, loader, mappings/namespace, API and library dependencies, toolchain, and **artifact hashes**. Runtime evidence additionally records client/server topology, feature flags, relevant config, resource/datapack state, tested integration set, and rendering environment where material.

**Freezing a snapshot's bytes is more than a lockfile.** Gradle changing dependencies can retain coordinates while contents change; checksum/signature verification is required in addition to version pinning.

**Four separate fields:**

| Field | Example values |
|---|---|
| Feasibility | satisfiable, unsatisfiable, unresolved |
| Implementation | native, adapted, translated, pending |
| Evidence | compile, boot, contract, integration, performance, feel — each pass/fail/unknown |
| Release policy | experimental, supported, blocked, retired |

- A solver establishes satisfiability of declared constraints, not runtime correctness. Return reasons for unsatisfied requirements.
- **Required capabilities fail resolution when missing; only optional features may be pruned** under declared policy. The machine must never silently redefine the product until it becomes buildable.
- **Availability semantics:** class presence, enabled gameplay, obtainability, and compatible behavior are different facts. (v0.1's `bundle >=1.17` floor was imprecise; the released bundle arrived in 1.21.2 and changed prototype controls.)

---

## 10. Deltas and the corpus

**Deltas are directed, guarded, versioned transformations** — not automatically bidirectional.

Every reusable recipe records: identity and version; source/destination conditions; semantic intent; implementation; dependencies; conflicts and ordering; preconditions; postconditions; known losses; affected contracts; positive and negative fixtures; provenance and licensing; owner; evidence against exact hashes.

**Namespace ≠ version.** Yarn's `CustomPayload` and official `CustomPacketPayload` name the same 1.21.1-era concept under different namespaces. Namespace is explicit in the model. Real version-hop deltas look like 1.20.4→1.20.5 networking or 1.21.11→26.1 (Java 25, deobfuscated jar, Loom rename).

**Reverse recipes are separate, conditional, tested assets.** If two distinguishable newer states collapse into one older state, the inverse cannot recover the distinction without retained information. Reverse may be exact, conditional, lossy, dependent on retained data, or unsupported — an honest backporting system.

**Prefer composing target models and rendering from known inputs** over stacking textual patches. Where rewrites are necessary: structured matches, exact applicability guards, ordering-conflict tests.

**Refusals are scoped evidence, not eternal truth.** Store input fingerprint, reason, invalidation trigger. New upstream releases reopen decisions; transient fetch failures do not become impossibility records.

**The corpus must support invalidation.** A defective shared recipe can spread one mistake across many mods. The registry identifies dependent artifacts, revokes stale claims, triggers rebuilding or relabeling.

> **The valuable asset is not a large pile of deltas. It is a trustworthy collection of applicable deltas.**

---

## 11. Verification and the oracle boundary

**Three independent evidence sources:**

1. **Authored contract properties** — intended behavior: conservation, legal slots, correct rejection, identity preservation, **positive obligations**.
2. **Reference characterization** — existing behavior including known defects; defects get explicit decisions (preserve / fix / deprecate); never silently blessed through differential agreement.
3. **Real-target observations** — real hooks firing, world-state transitions, protocol outcomes, save/reload, packaged artifacts.

**Compare observations, not self-reported effects.** Identical traces can coexist with callbacks that never fire or mutations that differ from their receipts. Normalize only explicitly irrelevant variation; a trace comparator must not erase item loss, missing permission checks, or failed persistence.

**Scoped assurance statement:**

```text
Artifact A satisfied contract C under test suite S and environment E,
with approved differences D and unresolved obligations U.
```

**Minimum acceptance predicate** (write this before any registry service): for a required claim, acceptance requires that the expected tests **actually executed**; results satisfy the **protected policy**; artifact and environment identities **match**; and no blocking obligation or applicable revocation remains. **A green process exit is one input, not the acceptance decision.** A test run that exits 0 without running a required fixture yields *insufficient evidence* — support is not granted.

**History is immutable; applicability is not.** Historical execution records are never rewritten. Discovering a defective recipe preserves the old execution record, attaches the contradiction, and updates the support decision. Revocation is auditable rather than a mutable collection of current opinions.

**The harness must prove it can reject bad work.** Deliberately incorrect implementations — `NoOpBackend`, wrong quantity, ignored slot restriction, lost metadata, feedback before commit, missing persistence — must be rejected for the intended reason. This precedes generating many tests from the same manifest that declares the features.

**Deficits** specify affected feature, exact target conditions, observable predicate, user impact, approved bound, regression fixture, owner, invalidation policy. A particle substitution can be an approved fidelity difference; unexplained item loss cannot claim successful support of a conservation contract.

**Replay levels:** pure planner replay from recorded inputs; backend replay against a controlled fixture; real-game reproduction with matching dependencies and environment. Production diagnostics are bounded and opt-in.

**Evidence bundle** binds source, recipes, test definitions, reference version, environment, declared deficits, reports, artifact digest. Changes invalidate affected claims.

**Verification ladder:** unit and property tests; adapter-contract fixtures; packaged-artifact boot tests; dedicated-server and client tests; multi-client protocol tests; save/reload and migration fixtures; integration profiles; performance comparisons; authored feel checks. Adopt Fabric's server/client game-test infrastructure and production-run support; NeoForge's `runGameTestServer` exit code as CI gate. Distinguish gameplay failures from infrastructure failures and inconclusive results. Retrying until green is not a support policy.

---

## 12. Agent operation, coexistence, and supply chain

- Start with a **deterministic workflow orchestrator**; add parallel agents when independent work exists.
- Agents **propose**. **Protected policies control acceptance tests, approved deficits, and publication.** A repair must not authorize itself by weakening its own gate.
- **Enforcement timing is explicit:** acceptance-policy protection and the separation of release credentials are **immediate** — not deferred until agent autonomy increases. Quotas, scheduling, and broader supply-chain controls arrive when measured bottlenecks justify them.
- Run untrusted builds in isolated, disposable environments without release credentials. Resolve and verify dependencies; separate artifact verification from publication; pin corpus versions; record provenance.
- Track licenses and origins at recipe, copied-code, and generated-artifact boundaries.

**Runtime coexistence — the smallest workable model (default for v1):**

- Predominantly **development-time machine with private target code**. No shared runtime library.
- Contracts are compile-time; each artifact carries its own generated/compiled implementations.
- Shared identifiers (channels, config keys, hook registrations) are namespaced under the mod id; no cross-mod global state.
- Two mods pinning different major contract versions simply carry different private code — they must not conflict.
- **Co-installation is a tested claim, not an assumption:** Gate D1 includes a fixture that installs two independently built artifacts together.

---

## 13. Economics and adoption

**Target cost model:**

```text
new contracts + target-native residual work + cross-contract integration
+ verification + distribution + ongoing maintenance
```

**Measure, not assume:** human effort; unseen-target completion; unchanged recipe reuse across unrelated mods; residual native work; regression escape rate; runtime overhead; compute/storage cost; maintenance after upstream changes.

**Three cost phases, tracked separately:** first-target investment; subsequent-target work; maintenance over upstream changes. The useful question is not whether the next port is cheaper — it is whether accumulated savings or assurance benefits justify the machine's creation and maintenance.

**The comparison baseline must be fair:** comparable tools, competent native code organization, and the same acceptance standard. Comparing against a deliberately repetitive manual port establishes nothing.

**Port Surface Area is a diagnostic, not the objective.** A one-line workaround can be dangerous; a longer explicit adapter can be correct and cheap. A line budget triggers **explanation**, not mandatory abstraction; complexity moved into helpers or generators counts.

**Demand-driven builds** defer compute and distribution for known recipes. An unknown target initiates resolution and possibly engineering. Honest experimental/support labels.

**Incremental adoption:**

> Manage existing native projects → extract useful seams → reuse proven contracts → automate recurring transformations.

**Abandonment criteria for an abstraction** (not for the project):

- Unrelated consumers repeatedly require **incompatible changes** to a supposedly shared contract → stop generalizing it. Keep separate native implementations; reuse the surrounding build and verification infrastructure.
- Maintaining a shared backend costs more than the duplicated native implementations it replaces → retain it only for an explicitly valued benefit (e.g., assurance), not because shared code is inherently superior.
- A held-out target repeatedly requires redesign → **record failure of the generalization claim.** Do not redefine the tested semantic family after seeing the result.

> **Native fallback preserves the product, not automatically the abstraction.**

---

## 14. Roadmap: gates that could disprove the architecture

**First supported domain** (deliberately narrow, not the capability ceiling):

> **Bounded, single-variant transfer between disjoint, non-voiding providers whose required guarantees are established.**

Deliverable package: normative behavior; pure reference model; deliberately incorrect implementations (including `NoOpBackend`); a native reference adapter; a packaged-game fixture. The contract specifies positive transfer behavior, identity, capacity, access, lifetime, aliasing policy, transaction ownership, final receipts, and rejection semantics.

**Decisive counterexamples:**

| Counterexample | Required result |
|---|---|
| Backend always moves zero despite an admissible transfer | Positive-behavior tests reject it |
| Nested operation succeeds; parent aborts | No final success receipt or successful feedback survives |
| Source and destination views overlap | Correct alias-aware behavior, or explicit pre-mutation rejection |
| Two same-ID items differ in meaningful state | No improper merge or metadata loss |
| Provider no longer matches plan validity conditions | Defined stale handling; no speculative application |
| Test runner reports success but omits a required fixture | Evidence insufficient; support not granted |

| Gate | Work | Must demonstrate |
|---|---|---|
| **A — Trustworthy baseline** | Pin the 1.21 source and tool artifacts; characterize behavior; add independent Flow invariants; add deliberately incorrect implementations | Harness rejects the bad implementations for the intended reason, including `NoOpBackend`; reference defects distinguished from port defects |
| **B — Separate the axes** | Loader × era matrix: reference-era Fabric + same-era NeoForge; modern pair; pre-component legacy later. Preflight artifact availability | Loader adaptation and version migration separately understood; tool substrate viability proven before a large plugin suite |
| **C — One vertical slice** | One Flow operation end-to-end: typed inputs, native executor, receipt, success presentation, failure behavior. Rest of Osmosis stays native | A shipped-quality slice, not a universal IR prototype |
| **D1 — Independent reuse + coexistence** | Same versioned contract/backend reused in an unrelated consumer with independently chosen requirements; co-installation fixture | Reuse without copying or Osmosis-specific generalization; both artifacts coexist under the declared runtime model |
| **D2 — Depth probes** | Small content/persistence, rendering/input, and lifecycle/mixin probes | Native extensions integrate cleanly; difficult semantics do not contaminate shared contracts. Separate hypothesis from D1 — do not block reuse behind a larger engineering program |
| **E — Held-out generalization** | Freeze contracts and corpus; attempt an unseen target or consumer; compare against a fair manual baseline | Actual reuse, new engineering, failures, cost recorded; newly authored work is not called "automatic reuse" |
| **F — Controlled expansion** | Caching, release automation, scheduling, auditing, agent parallelism — as measured bottlenecks justify them | Expansion follows evidence |

A gate failure **revises the abstraction or contract** — and triggers the abandonment criteria above when the failure repeats.

**Substrate:** keep build-tool providers replaceable; choose the first by a representative build-and-test spike, not the longest loader list. Separate the machine's JVM from artifact runtimes; choose an explicit legacy strategy (shared kernel at a lower language level, source generation, or per-era modules sharing contracts — sealed interfaces are Java 17+).

---

## 15. Decisions locked / open

**Locked**

- Domain contracts over a frozen eight-method runtime; safety **and** positive obligations.
- Ordinary typed code and small plans before any general-purpose IR or DSL.
- Transaction ownership and the finality rule.
- Trait meaning/basis/applicability separation; no silent evidence upgrades.
- Minimum acceptance predicate; immutable history, mutable applicability.
- Native extensions and incremental adoption first-class; abandonment criteria defined.
- Directed, guarded recipes; exact artifact hashes beyond lockfiles.
- Runtime model default: development-time machine, private target code, no shared runtime library, namespaced identifiers, co-installation tested.
- Acceptance-policy protection and credential separation enforced immediately.
- Flow is the first demanding contract; validate before building the machine.
- Build-tool providers replaceable; semantics owned by the machine.

**Open**

- Name (Mycelium provisional; Kiln, Grain alternates).
- First substrate (Unimined vs Stonecutter + Modstitch) — decided by Gate B spike.
- Contract format and corpus storage.
- Java-era strategy for legacy targets.
- Governance enforcement timeline beyond the immediate items.

---

## Appendix A — Consumer source audit (redacted)

Consumer source inventories, implementation findings, and audit commands are omitted from this publication edition.

## Appendix B — Glossary

- **Contract** — typed, versioned domain capability with safety properties and positive obligations; the reusable unit.
- **Effect category** — one of eight audit classifications; taxonomy, not architecture.
- **Trait** — capability property with meaning, basis, and current applicability.
- **Receipt** — record of what actually committed at the promised final boundary; drives feedback.
- **Provisional result** — work accepted into a parent transaction; not a final receipt.
- **Delta / recipe** — directed, guarded, versioned transformation with evidence.
- **Deficit** — declared, approved, fixture-backed behavioral difference; never a wildcard.
- **Target profile** — exact resolved environment description including artifact hashes.
- **Acceptance predicate** — the protected rule deciding whether evidence grants support.
- **PSA** — Port Surface Area; diagnostic metric.
- **Evidence bundle** — artifact-bound record of claims, tests, environment, deficits, digests.

## Appendix C — Changelog: v0.2 → v0.3

Integrated from the second red-team review (85/100), with primary-source verification:

1. **Added positive behavioral obligations** alongside safety properties; `NoOpBackend` is a required rejection fixture; "does not damage the world" ≠ "implements the feature."
2. **Added transaction ownership and the finality rule**; provisional nested results; unconfirmed ≠ `Rejected`. Verified against Fabric `Transaction` javadoc (nested commit may be cancelled; side effects deferred until outer close).
3. **Added composition requirements** to the coverage claim.
4. **Added trait meaning/basis/applicability** with evidence bases and no silent upgrades; unknown/unsupported/known-incompatible preserved. Verified: `supportsInsertion` "or in doubt"; wrapper independence caution; `VoidingResourceHandler` exists.
5. **Added item identity trichotomy** and opaque identity tokens for the first slice; portability claims separated; schema versions for saved state and wire protocol.
6. **Added minimum acceptance predicate**; immutable execution history vs mutable applicability; missing-fixture = insufficient evidence.
7. **Added runtime coexistence default** and co-installation fixture; Gate D split into D1 (reuse + coexistence) and D2 (depth probes).
8. **Added abandonment criteria** for abstractions; fair-baseline requirement; three cost phases.
9. **Fixed** taxonomy dimension mixing (orthogonal attributes), removed the unsupported "~90%" claim, resolved the layer/purity conflict, bounded "no framework tax," added artifact-hash freezing beyond lockfiles.
10. **Grounding appendix** now carries versions, retrieval dates, and exact citations (Appendix D).

## Appendix D — Grounding and verified citations

**Verified primary sources (retrieved 2026-09-20):**

| Claim | Source | Verbatim evidence |
|---|---|---|
| `supportsInsertion` is not a promise | Fabric API `0.102.0+1.21`, `Storage` javadoc, maven.fabricmc.net | *"true otherwise or in doubt"* |
| Nested commit is provisional | Fabric API `0.102.0+1.21`, `Transaction` javadoc | *"may still be cancelled later if a parent transaction is cancelled"*; side effects *"should be deferred until after the outer transaction is closed"* |
| Wrapper rollback assumptions | Fabric Wiki, transfer API item storage (last modified 2025-12-18) | *"Do NOT use it if [slots are not independent] — side effects that get left behind when a transaction is reverted"* |
| Voiding is supported behavior | NeoForge, "The Transfer Rework," 2025-09-30 | *"VoidingResourceHandler: resource handler that voids any inserted resource"* |
| No universal legacy wrapper | NeoForge, "The Transfer Rework," 2025-09-30 | *"it is not possible to provide a wrapper that turns any IItemHandler into an ResourceHandler"* |
| Transfer convergence | NeoForge, "The Transfer Rework," 2025-09-30 | `ResourceHandler<ItemResource>` replaces `IItemHandler`; transactions with parent/commit semantics |

**Research grounding:**

- Delta-oriented programming: DeltaJ/DeltaJava (Schaefer et al.) — product = core + deltas.
- Feature-model analysis: Mendonça/Wąsowski/Czarnecki, "SAT-based analysis of feature models is easy" (SPLC 2009).
- Bidirectional transformations / lenses: round-trip discipline as tests.
- Algebraic effects / free monads: sealed interfaces + interpreters (shape, not runtime).
- Differential testing: trace equivalence, divergence baselines.
- Demand-driven build systems: Mokhov et al.; caching + constructive traces.
- Ecosystem precedents: LuckPerms (api/common/platforms), ViaVersion (api/common/platforms/universal), Unimined, Stonecutter/Modstitch, Sinytra Connector + FFAPI, Forgix, Fabric/NeoForge game-test infrastructure.
- Artifact integrity: Gradle dependency verification (checksums/signatures) beyond version locking for changing modules.
