# rule.effects@1 — Contract Specification

| | |
|---|---|
| Contract id | `rule.effects@1` (implementation namespace `mycelium.rule_effects.v1`) |
| Status | Design-time contract package; Mycelium DESIGN v0.4 §6–§8, §11 |
| Domain | Applying and removing a rule's effects (attribute modifiers, flags, status effects) on a bounded set of entities, with stable ids, locale scope, and transactional finality |
| Reference model | `src/` — pure Java 17, no Minecraft or loader imports, no dependencies beyond `harness/src` |
| Claims discipline | Everything here is design-time. No claim is evidence of behavior on a real game target; that needs the native adapter and gametests (`rule_low_gravity`, `rule_no_stack`) |

---

## 1. Scope

- One rule, one entity set, one list of effect specs per operation. The entity set is the locale's entity set as computed by the caller; this contract never decides scope, it only respects it.
- Effects are keyed by a **stable id** (`EffectSpec.id()`): the modifier id for attributes, the flag name for flags, the effect id for status effects. Two applications of the same rule refer to the same ids.
- Removal is by ledger: the caller passes an `EffectLedger` that says which ids the rule owns on each entity. The executor never guesses.
- Providers participate in rollback: every mutation registers an undo on the transaction.
- Out of scope in v1: effect priority between rules, partial application (one entity fails mid-way is `Rejected(APPLY_FAILED)` and the transaction is expected to be aborted by the owner), attribute value arithmetic (the provider owns it).

## 2. Types

| Type | Meaning | Invariants |
|---|---|---|
| `EntityId` | Opaque entity token | Non-blank |
| `Scope` | Locale id the operation is scoped to (documentation of intent; the entity set is the enforcement) | Non-blank |
| `EffectSpec` | Sealed: `Attribute(attribute, op, value, modifierId)`, `Flag(flag)`, `StatusEffect(effect, amplifier)` | `id()` non-blank |
| `EffectPort` | Provider: `apply`, `remove`, `active`, fixture hook `know` | Mutates only inside a `Tx`; registers undo |
| `Tx` | Transaction with parent/nested semantics and deferred outer-commit actions | Undo in reverse on abort; nested commits cancellable by parent |
| `Receipt` | Sealed: `ApplyReceipt`, `RemoveReceipt` — `(ruleId, entities, ids)` | Non-empty entities and ids; zero effect is a rejection |
| `EffectOutcome` | `Committed(receipt)` / `Rejected(reason)` / `Unsupported(detail)` | See §3 |
| `EffectFeedback` | Presentation channel | Receives receipts only at the outermost commit |
| `EffectLedger` | Immutable entity → rule → ids | `withApplied`, `withRemoved`, `idsFor`, `allIds`, static `reconcile(ledger, port, entities)` → `List<Leak>` |
| `Leak` | `ORPHAN_ON_PORT` (active but unowned) or `MISSING_ON_PORT` (owned but inactive) | |

## 3. Outcomes

- **`Committed(receipt)`** — every id in the receipt is active on every entity in the receipt (apply), or no longer active (remove), once the outermost transaction commits.
- **`Rejected(reason)`** — `EMPTY_SCOPE`, `EMPTY_EFFECTS`, `APPLY_FAILED`, `NOTHING_TO_REMOVE`, `REMOVE_FAILED`. No state change survives a rejection (the owner aborts on `*_FAILED`).
- **`Unsupported(detail)`** — reserved for providers that cannot represent a spec kind; not producible by the reference port.

## 4. Laws (safety)

- **L1 Symmetry** — after `apply` then `removeRule` with the ledger built from the apply receipt, `active(e)` equals its value before the apply, for every entity in scope.
- **L2 Stable ids, no stacking** — applying the same rule twice leaves `active(e)` with exactly the spec ids: size unchanged after the second apply, receipt ids identical.
- **L3 Scope** — entities outside the entity set are never touched. `active(outside)` is unchanged after any operation.
- **L4 Finality** — no feedback before the outermost commit. If the outer transaction aborts, no effect remains active and no feedback survives.
- **L5 Exactness** — after a committed apply, `active(e)` equals exactly the ids of the specs (no extra ids minted by the provider).
- **L6 Zero effect is a rejection** — removing a rule that owns nothing is `Rejected(NOTHING_TO_REMOVE)`, never a receipt.
- **L7 Ledger truth** — `reconcile(ledger, port, entities)` is empty after every committed apply and remove when the ledger was updated from the receipts.

## 5. Positive obligations

- **P1** — After a committed apply, `active(e)` contains every spec id, for every entity in scope. A port that reports success and applies nothing is incorrect, not conservative.
- **P2 (anti-evasion)** — A valid apply (non-empty scope, non-empty effects, port that can represent every spec) must be `Committed`, never `Unsupported` or `Rejected`.

## 6. Edge semantics

| Condition | Outcome |
|---|---|
| empty entity set | `Rejected(EMPTY_SCOPE)` |
| empty effect list | `Rejected(EMPTY_EFFECTS)` |
| id already active on an entity | not re-applied; still counted in the receipt |
| `port.apply` returns false | `Rejected(APPLY_FAILED)`; owner aborts the transaction |
| rule owns nothing on any entity in scope | `Rejected(NOTHING_TO_REMOVE)` |
| id owned by ledger but not active on port | skipped on remove; reconcile reports `MISSING_ON_PORT` |

## 7. Traits and admission

The reference port declares nothing; the harness trusts nothing. Every fixture in the rejection matrix uses a port with the same interface and is caught by executed fixtures, not by declarations.

## 8. Fixtures (normative)

| Fixture | Setup | Expectation |
|---|---|---|
| T1 apply positive | entities A, B in scope, OUTSIDE known; 3 specs | `Committed`; receipt ids = spec ids; no feedback before commit; after commit `active(A) == ids` exactly, `active(OUTSIDE)` empty; feedback once |
| T2 empty scope / empty effects | | `Rejected(EMPTY_SCOPE)`, `Rejected(EMPTY_EFFECTS)`; no mutation; no feedback |
| T3 no stacking | apply, commit, apply again | second `Committed` with same ids; `active(A)` size unchanged and exact |
| T4 symmetry | apply, commit; removeRule with ledger from receipt | `Committed`; remove ids = spec ids; `active` equals pre-apply; two feedback events |
| T5 nothing to remove | empty ledger | `Rejected(NOTHING_TO_REMOVE)`; no feedback |
| T6 finality on abort | nested apply, nested commit, outer abort | `active(A)` empty; no feedback |
| T7 finality on commit | nested apply, nested commit | no feedback before outer commit; after: active and feedback once |
| T8 ledger reconcile | two rules on A, one on B; remove one rule | no leaks after apply, no leaks after remove; other rule untouched |
| T9 anti-evasion | valid apply | `Committed`, not `Unsupported`/`Rejected`; active contains ids |

Labels are `T<n>:<kebab>`; see `harness/RuleEffectsContractTests.java`.

**Rejection matrix** — deliberately incorrect implementations the harness must catch for the intended label:

| Implementation | Violates | Intended label |
|---|---|---|
| `StickyEffectPort` (remove is a no-op) | L1 | `T4:symmetry` |
| `StackingEffectPort` (mints a fresh id per apply) | L2, L5 | `T3:no-stacking` |
| `GlobalEffectPort` (applies to every known entity) | L3 | `T1:scope-untouched` |
| `NoOpPort` (claims success, does nothing) | P1 | `T1:active-contains-ids` |
| `PrematureReceiptExecutor` (feedback at execution) | L4 | `T6:no-feedback-after-abort` |

## 9. Versioning and packaging

- This is `rule.effects@1`. Changing laws, outcomes, the `EffectSpec` set or the ledger semantics produces `@2`.
- `mycelium.rule_effects.v1` is development-time. Consumers import `model` under their own artifact-owned prefix; `fixtures` and `harness` never ship.

## 10. Running the harness

From the Mycelium root: `./run-contracts.sh rule-effects` (or `.\run-contracts.ps1 rule-effects`). Expected: reference clean, every matrix entry `CAUGHT`, exit code 0.

## 11. Explicit non-claims

- No Minecraft, Fabric or NeoForge code was executed to produce this package.
- The native adapter (attribute modifiers with stable resource-location ids, per-entity flags through attachments, status effects) is its consumer's work. Until one exists and a gametest like `rule_no_stack` passes on a real server, this contract has no game-target evidence.
- Rule priority, effect composition between rules, and attribute arithmetic are named here as out of scope rather than silently omitted.
