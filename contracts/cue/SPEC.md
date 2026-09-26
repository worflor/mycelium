# presentation.particle-cue@1 — Contract Specification

| | |
|---|---|
| Contract id | `presentation.particle-cue@1` (implementation namespace `mycelium.cue.v1`) |
| Status | Mycelium contract package, sibling to `inventory.transfer@1` (see `../flow/SPEC.md`, `../../CONTRACT-PACKAGE.md`) |
| Domain | Scheduling a presentation cue (particle/sound/etc.) against a profile-resolved key, deferred to the outermost commit of the operation it rides on |
| Reference model | `src/` — pure Java 17, no Minecraft or loader imports, no dependencies |
| Claims discipline | Everything in this package is design-time. No claim here is evidence of behavior on a real game target; that requires a native adapter and packaged-game fixtures of a later gate |

---

## 1. Scope

- One cue per schedule call, resolved through exactly one profile lookup.
- A cue rides an existing operation's `Tx` (mirrors `flow`'s `FlowTransaction`/`player-state`'s `Tx`): it never opens its own top-level transaction, and its emission is deferred to that transaction's outermost commit.
- Profile resolution is total-or-explicit: every lookup either resolves directly, resolves through a declared fallback, or is explicitly `Unsupported`. There is no silent skip.
- Out of scope: the actual particle/sound/render backend, batching multiple cues into one packet, and cue ordering across unrelated operations — those belong to the native adapter.

## 2. Types

| Type | Meaning | Invariants |
|---|---|---|
| `Cue` | `(kind, profile, key)` | All three non-blank |
| `CueSink` | Where a scheduled cue actually lands | `emit(Cue)` |
| `Receipt` | Stand-in for any operation's receipt; a cue is scheduled against one | `operationId` non-blank |
| `ProfileId` | Opaque identity token for a profile | Non-blank token |
| `ProfileMap` | Profile resolution table | `resolve(profile, key)` is a direct lookup; `hasFallback(profile)` declares whether a fallback exists; the fallback itself is resolved under the reserved key `ProfileMap.FALLBACK_KEY` |
| `Tx` | Transaction scope with parent/nested semantics and deferred outer-commit actions | Same shape as `flow.v1.model.FlowTransaction` and `player-state`'s `Tx` |
| `CueOutcome` | `Scheduled` / `Unsupported` | See §3 |
| `CueScheduler` | `schedule(Tx, Receipt, Cue, CueSink) -> CueOutcome` | Profile resolution is the reference scheduler's own concern (constructor-injected `ProfileMap`), not part of the interface signature |

## 3. Outcomes

- **`Scheduled(cue)`** — the cue resolved; `cue` is the *resolved* cue (its `key` is the mapped value, not necessarily the caller's original key). Emission to the sink is deferred to the outermost commit.
- **`Unsupported(detail)`** — the profile has neither a direct mapping for the requested key nor a declared fallback. Checked before any emission is scheduled.

## 4. Laws (safety)

- **L1 Deferred emission** — no cue reaches the sink before the outermost commit of the `Tx` the schedule call rode on.
- **L2 Exactly one cue per receipt** — a single `schedule` call, once committed, results in exactly one `emit` call.
- **L3 No cue for an aborted operation** — if the outermost transaction aborts, no cue ever reaches the sink, regardless of how many nested transactions committed underneath it.
- **L4 Total-or-explicit profile lookup** — a missing mapping with no declared fallback is `Unsupported`; it is never silently treated as `Scheduled` with nothing emitted.

## 5. Positive obligations

- **P1** — Every committed receipt whose cue resolved (`Scheduled`) yields its cue at the sink, exactly once, with the resolved key the profile map declared. A scheduler that reports `Scheduled` without ever emitting, or that ignores the profile map entirely, is incorrect, not conservative — caught by the same positive-obligation discipline `CONTRACT-PACKAGE.md` rule 3 requires of a `NoOp` fixture.

## 6. Edge semantics

| Condition | Outcome |
|---|---|
| direct mapping present for `(profile, key)` | `Scheduled` with the mapped key |
| no direct mapping, but `hasFallback(profile)` is true | `Scheduled` with the fallback-resolved key |
| no direct mapping and no fallback | `Unsupported`; nothing scheduled, ever |
| schedule rides a nested `Tx` | the outcome (`Scheduled`/`Unsupported`) is known immediately; the emission (if any) waits for the *outermost* commit |
| the nested `Tx` commits but the outer one later aborts | no emission ever reaches the sink |

## 7. Traits and admission

This contract has no trait-gated providers: `ProfileMap` resolution is total-or-explicit by construction (§4 L4), so there is no separate "unqualified provider" admission check — a missing mapping is already a first-class, explicit outcome rather than a capability gap.

## 8. Fixtures (normative)

Reference fixture set executed by `CueContractTests`:

| Fixture | Covers | Expectation |
|---|---|---|
| T1 positive resolution | L1, P1 | direct mapping resolves; no emission before commit; exactly one emission, with the resolved key, after commit |
| T2 fallback resolution | L4, P1 | missing direct mapping but a declared fallback resolves and emits the fallback key |
| T3 unsupported | L4 | no mapping and no fallback → `Unsupported`; sink never receives anything, even after commit |
| T4 no cue before outer commit | L1 | scheduled under a nested `Tx`; empty sink after the nested commit; one emission only after the outer commit |
| T5 finality on abort | L3 | nested commit followed by outer abort leaves the sink permanently empty |
| T6 exactly one cue per receipt | L2 | two independent, distinct schedule calls each committed produce exactly two emissions, one per receipt |
| T7 seeded property fixture | P1 | 25 seeded profile keys, each scheduled and committed independently; every receipt's resolved cue is found in the sink exactly once |

**Rejection matrix** — deliberately incorrect implementations, each of which the harness must reject for the intended reason:

| Implementation | Violates | Caught by |
|---|---|---|
| `PrematureCueScheduler` (emits at schedule time, not deferred) | L1 | T1:no-cue-before-commit |
| `DoubleCueScheduler` (defers the emission twice per schedule call) | L2 | T6:exactly-one-cue-per-receipt |
| `SilentFallbackScheduler` (returns `Scheduled` with no emission when the mapping is missing and there is no fallback) | L4 | T3:unsupported |
| `NoOpScheduler` (never emits, always reports `Scheduled`, ignores the profile map) | P1 (anti-evasion) | T7:every-receipt-emitted-once |

## 9. Versioning and packaging

- This is `presentation.particle-cue@1`. Changes to laws, outcomes, or edge semantics produce `@2`; additions that preserve all laws may remain `@1`.
- The implementation namespace here (`mycelium.cue.v1`) is development-time. Emitted artifacts renamespace Mycelium-owned classes under the consuming mod's artifact-owned prefix; no shared Mycelium runtime ABI is assumed.

## 10. Running the harness

From the Mycelium root: `bash run-contracts.sh cue` (or `.\run-contracts.ps1 cue`), or `./gradlew myceliumVerify -Pmycelium.contracts=presentation.particle-cue@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 11. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package.
- No real particle, sound, or render backend was exercised; `MemorySink` is an in-memory stand-in for "the cue reached its destination," not evidence about any specific presentation channel.
- Batching, cross-operation ordering, and cue cancellation are out of scope for v1 and named here rather than silently omitted.
