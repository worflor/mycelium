# world.copy@1 — Contract Specification

| | |
|---|---|
| Contract id | `world.copy@1` (implementation namespace `mycelium.world_copy.v1`) |
| Status | Mycelium contract package, mirrors `../flow/SPEC.md` in layout and discipline |
| Domain | Bounded read of a block-token region from a source view, planned then written to a target port, within a per-call write budget |
| Reference model | `src/` — pure Java 17, no Minecraft or loader imports, no dependencies |
| Claims discipline | Everything in this package is design-time. No claim here is evidence of behavior on a real game target; that requires the native adapter and packaged-game fixtures of a later gate |

---

## 1. Scope

- Copies a single axis-aligned `Region` of block tokens (plus block-entity *presence*, not contents) from a
  read-only `SourceView` to a mutating `TargetPort`.
- One region per call. The whole region is planned before any write; there is no incremental/streamed variant
  in v1 (see DESIGN §8.2 for the tick-budgeted scheduler that composes many calls of this contract).
- `Transform` may rewrite the token written at each position (e.g. block substitution); it must be pure and
  total over the region — it never inspects mutable state and never fails.
- `SanitizePolicy` decides, per block-entity-bearing position, whether the target's block-entity data (its
  contents — the payload is opaque to this contract) is cleared after the write. The block token itself is
  always written; sanitize only ever clears entity data, never the token.
- Block-entity *contents* (e.g. chest inventory, sign text) are opaque to this contract: it tracks only
  whether a position carries a block entity (`hasBlockEntity`), never what is in it. A target that keeps
  contents across a sanitized copy is a duplication vector and is out of contract (`LeakyTarget`).
- Targets participate in **rollback** so that aborting the owning transaction restores prior target state.
  Sources are read-only and never mutated by a copy.
- Inspection (read + transform + sanitize decision) and application happen within one call, using an internal
  plan-then-apply structure (`WorldCopy.plan` then `WorldCopy.execute`). The budget check happens against the
  *planned* write count before any target mutation, so a rejected copy never partially applies.

## 2. Types

| Type | Meaning | Invariants |
|---|---|---|
| `Pos(x, y, z)` | Block coordinate | Plain triple; `offset(dx,dy,dz)` for translation |
| `Region(min, max)` | Axis-aligned box, `min` inclusive, `max` exclusive per axis | `max >= min` on every axis; a zero-length axis makes the region empty (`size() == 0`) |
| `BlockToken(token)` | Opaque identity of a block state | Non-blank string; equality is token equality |
| `Write(pos, token, blockEntity)` | One planned mutation | `pos`, `token` non-null |
| `CopyPlan(writes, sanitize)` | Full set of writes and the positions to clear, computed before any mutation | Immutable lists |
| `Budget(maxWritesPerCall)` | Ceiling on writes in one call | `>= 1` |
| `SourceView` | Read-only source port: `token(pos)`, `hasBlockEntity(pos)` | Must not mutate anything; reads outside any seeded area return the source's fallback token with no block entity |
| `TargetPort` | Write port: `traits()`, `set(pos, token, blockEntity, tx)`, `clearBlockEntity(pos, tx)`, `token(pos)`, `hasBlockEntity(pos)` | Mutation only inside a transaction; every mutation registers an undo |
| `Transform` | `apply(pos, sourceToken) -> token` | Pure, total, non-null result; `Transform.IDENTITY` passes the source token through unchanged |
| `SanitizePolicy` | `clear(pos, token) -> boolean` | Consulted only at positions where the source reports a block entity; `SanitizePolicy.ALL` / `.NONE` are the two constant policies |
| `CopyTx` | Transaction scope, parent/nested, deferred outer-commit actions | Same shape as `inventory.transfer@1`'s `FlowTransaction`: undo runs in reverse on abort, nested commits stay cancellable by the parent |
| `CopyReceipt(writes, sanitized)` | What actually committed | `writes >= 1` (zero writes is never a receipt); `0 <= sanitized <= writes` |
| `Feedback` | Presentation channel: `copied(receipt)` | Receives a receipt only at the outermost commit |
| `CopyOutcome` | `Committed` / `Rejected` / `Unsupported` | See §3 |
| `Trait` | `ROLLBACK_PARTICIPATION`, `BLOCK_ENTITY_CONTROL` | Declared by a `TargetPort`; checked before any mutation |
| `CopyExecutor` | `execute(source, target, region, transform, sanitize, budget, tx, feedback) -> CopyOutcome` | Functional; `WorldCopy::execute` is the reference |
| `WorldCopy` | Reference executor: `plan(...)` (pure) and `execute(...)` (plans, budget-checks, then applies) | `REQUIRED_TRAITS = {ROLLBACK_PARTICIPATION, BLOCK_ENTITY_CONTROL}` |

## 3. Outcomes

- **`Committed(receipt)`** — the outermost transaction committed and every position in the region now carries
  the transformed source token, with `receipt.writes()` equal to the region's size and `receipt.sanitized()`
  equal to the number of block-entity positions the policy chose to clear.
- **`Rejected(reason)`** — the request cannot produce a committed copy. Reasons: `EMPTY_REGION`,
  `BUDGET_EXCEEDED`. Rejection implies **no state change survives** — for `BUDGET_EXCEEDED` in particular, the
  plan is computed and measured *before* any write, so a budget rejection never leaves a partial copy.
- **`Unsupported(detail)`** — the target lacks a required trait. Checked **before any mutation**, and before
  the region is even inspected for emptiness.

## 4. Laws (safety)

- **L1 Fidelity** — after the outermost commit, every position in the region reads back on the target with
  the token `transform.apply(pos, source.token(pos))`. (No lossy/undercount writes.)
- **L2 Block-entity presence preserved** — where the source reports a block entity and the sanitize policy did
  not clear it, the target reports a block entity at that position after commit; where the source reports none,
  the target reports none.
- **L3 Sanitize clears entity data, keeps the token** — for every position the plan marks for sanitize, the
  target's block-entity flag is false after commit while its token is unchanged from what was written.
- **L4 No write outside the region** — a copy touches only positions inside the requested region; any state at
  positions outside it is unchanged after commit.
- **L5 Budget respected, no partial state** — if the planned write count exceeds the budget, the outcome is
  `Rejected(BUDGET_EXCEEDED)` and the target is left exactly as it was (no plan-then-apply straddle).
- **L6 Finality** — no success feedback before the outermost commit. If the outermost transaction aborts, no
  state change and no feedback survive; a nested commit's writes and deferred feedback remain cancellable by
  the parent until the parent itself commits.
- **L7 Zero writes is a rejection** — an empty region (`size() == 0`) is `Rejected(EMPTY_REGION)`, never a
  `Committed` receipt with `writes == 0` (the `CopyReceipt` constructor itself refuses `writes < 1`).

- **L8 (a sealed copy has no way out)** — `ExitPolicy` names the tokens that are exits (portals, gateways) and what
  replaces them; `WorldCopy.executeSealed` forgets them **after** the transform, so neither the source's exits nor
  one a transform makes survive into the copy (`T13`). Added 2026-09-23 from Somnium (a copied portal allowed an unintended exit from an isolated region). Additive: `execute` is unchanged; the sealed overload is new.

## 5. Positive obligations

- **P1** — Under the stated fixture assumptions (stable source, a target declaring the required traits, budget
  not exceeded): a copy of a non-empty region always commits, and `receipt.writes() == region.size()`. Writing
  fewer positions, or declining to write while reporting success, is incorrect, not conservative.
- **P2 (anti-evasion)** — A target that declares the required traits must not evade P1 by returning
  `Unsupported` or `Rejected` for an admissible request. Evasion is a positive-obligation violation.

Safety alone is satisfiable by doing nothing (an empty target trivially violates no per-write law); P1/P2 are
what make a no-op target fail.

## 6. Edge semantics

| Condition | Outcome |
|---|---|
| target lacks a required trait | `Unsupported(detail)` — checked first, before region inspection |
| `region.size() == 0` | `Rejected(EMPTY_REGION)` |
| planned write count `> budget.maxWritesPerCall()` | `Rejected(BUDGET_EXCEEDED)`; no writes reach the target |
| source position has no block entity | sanitize policy is never consulted there; target block-entity flag is false |
| source position has a block entity and policy declines to clear | token and block-entity flag both written; contents (opaque) are the target's own concern |
| `Transform` maps two different source tokens to the same output token | both positions are written faithfully with that output token; identity is per-position, not de-duplicated |

## 7. Traits and admission

Required traits for this contract: `ROLLBACK_PARTICIPATION`, `BLOCK_ENTITY_CONTROL`. A missing trait yields
`Unsupported` before any mutation and before the region is even measured for emptiness.

**Basis note (mirrors `../flow/SPEC.md` §7):** a trait declaration is a claim, not evidence. The harness does not
trust declarations — it executes fixtures. A failing fixture blocks a claim regardless of declaration, and all
fixtures in the rejection matrix declare the required traits (the one exception, `UnqualifiedTarget`, exists
specifically to exercise the `Unsupported` path in T10 and is not part of the rejection matrix itself).

## 8. Fixtures (normative)

Reference fixture set executed by `WorldCopyContractTests`:

| Fixture | Setup | Call | Expectation |
|---|---|---|---|
| T1 identity copy | 3×2×3 region, patterned source (token varies by position) | identity transform, sanitize ALL, generous budget | `Committed`, `receipt.writes() == 18`; target equals source over the region; one feedback event |
| T2 sanitize clears | 2×1×2 region, stone fill, one chest + one sign (block entities) | sanitize ALL | `receipt.sanitized() == 2`; target reports no block entity at either position; tokens (`chest`/`sign`) still kept |
| T3 block-entity preserved | same setup as T2 | sanitize NONE | `receipt.sanitized() == 0`; target still reports a block entity at both positions |
| T4 no write outside region | 2×2×2 region, seven positions outside it pre-seeded with a distinct `outside` token | identity, sanitize ALL | all seven outside positions unchanged after commit; region itself still matches source |
| T5 budget exceeded | 4×4×4 region (64 writes), `Budget(10)` | identity, sanitize ALL | `Rejected(BUDGET_EXCEEDED)`; target entirely void (no partial writes); no feedback |
| T6 finality on abort | 2×2×2 region, nested tx under an outer that aborts | identity | after outer abort, target entirely void; no feedback |
| T7 finality on commit | same as T6, outer commits | identity | no feedback before outer commit; after outer commit, target matches source; exactly one feedback event |
| T8 transform applied | 2×1×2 region, stone fill except one chest position kept as-is | `stone -> tuff` transform, sanitize ALL | every stone position now `tuff`; the chest position keeps its original token |
| T9 seeded identity (property fixture) | 5 seeds (`Seeded(1..5)`) driving random region origin/size and per-position token/block-entity | identity, sanitize NONE, generous budget | for every seed: target equals source over the region; `receipt.writes() == region.size()`; block-entity flags match position-for-position |
| T10 unsupported trait | `UnqualifiedTarget` (declares only `ROLLBACK_PARTICIPATION`) | any admissible region | `Unsupported`; zero positions written on the underlying memory |
| T11 anti-evasion | admissible request against a target declaring the required traits | identity | must be `Committed`, never `Unsupported`/`Rejected` |
| T12 empty region | zero-length region (`0 × 3 × 3`) | identity | `Rejected(EMPTY_REGION)`; no feedback |

**Rejection matrix** — deliberately incorrect implementations, each of which the harness must reject for the
intended reason:

| Implementation | Violates | Caught by |
|---|---|---|
| `NoOpTarget` (declares traits, honest inspection, never writes) | P1 / P2 | `T1:target-equals-source` |
| `LossyTarget` (drops block-entity presence silently on every write) | L2 | `T3:block-entity-preserved` |
| `LeakyTarget` (ignores clear requests; sanitized positions keep their block entity) | L3 | `T2:sanitized-cleared` |
| `OutOfRegionTarget` (writes one extra position outside every fixture region on every `set`) | L4 | `T4:no-write-outside-region` |
| `OverrunExecutor` (ignores the budget, writes and reports success regardless) | L5 | `T5:budget-rejected` |
| `PrematureReceiptExecutor` (emits feedback at execution time instead of at the outermost commit) | L6 | `T6:no-feedback-after-abort` |
| `UnsealedExecutor` (ignores the exit policy) | L8 | `T13:no-exit-copied` |
| `SealFirstExecutor` (seals before the transform) | L8 | `T13:transform-cannot-open-an-exit` |

`UnqualifiedTarget` exercises the `Unsupported` edge (T10) but is not a rejection-matrix entry: it is not
"deliberately incorrect" under an admissible request, it is honestly inadmissible.

## 9. Versioning and packaging

- This is `world.copy@1`. Changes to laws, outcomes, or edge semantics produce `@2`; additions that preserve
  all laws may remain `@1`.
- The implementation namespace here (`mycelium.world_copy.v1`) is development-time. Emitted artifacts
  renamespace Mycelium-owned classes under the consuming mod's artifact-owned prefix; no shared Mycelium
  runtime ABI is assumed.
- This contract is one building block of a tick-budgeted copy pipeline; a per-tick section scheduler, hydration,
  and a shrink policy are composition concerns above this contract, not part of it.

## 10. Running the harness

From the Mycelium root: `bash run-contracts.sh world-copy` (or `.\run-contracts.ps1 world-copy`), or `./gradlew myceliumVerify -Pmycelium.contracts=world.copy@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 11. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package.
- The native reference adapter and packaged-game fixture (real chunk sections, real block-entity contents,
  real budget numbers under load) are later artifacts; until they exist, this contract has no game-target
  evidence. A per-tick main-thread budget (a consumer's, such as 3 ms) is a design target for the composing
  scheduler, not a claim this package measures or enforces.
- Block-entity *contents* (chest inventories, sign text, etc.) are explicitly out of scope: this contract
  tracks presence only. A target that preserves presence but drops or corrupts contents does not violate any
  law in this package.
- Aliasing between source and target (copying a region onto itself or an overlapping dimension), streaming/
  incremental copies across multiple calls, and concurrent mutation of the source during a copy are out of
  scope for v1 and named here rather than silently omitted.
