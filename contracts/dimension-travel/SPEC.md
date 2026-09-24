# dimension.travel@1 — Contract Specification

| | |
|---|---|
| Contract id | `dimension.travel@1` (implementation namespace `mycelium.dimension_travel.v1`) |
| Status | Mycelium contract package, mirrors `../flow/SPEC.md` in layout and discipline |
| Domain | Move a player between dimensions (or same-coordinate across a seam) with position and inventory intact, confirming arrival before reporting success |
| Reference model | `src/` — pure Java 17, no Minecraft or loader imports, no dependencies |
| Claims discipline | Everything in this package is design-time. No claim here is evidence of behavior on a real game target; that requires the native adapter (`FabricDimensions.teleport` / vanilla `teleportTo`, TDD §7.1) and packaged-game fixtures of a later gate |

---

## 1. Scope


## 2. Types

| Type | Meaning | Invariants |
|---|---|---|
| `PlayerId(id)` | Opaque player identity | Non-blank string |
| `DimensionId(id)` | Opaque dimension identity | Non-blank string |
| `Position(dimension, x, y, z, yaw, pitch)` | A place and facing in a dimension | Non-null `dimension`; equality is exact component equality (no tolerance) |
| `Pose` | `STANDING` \| `SLEEPING` \| `GETTING_UP` | Arrival pose; not independently queryable on `TravelPort` in v1 |
| `ItemToken(id, count)` | One inventory stack | Non-blank `id`; `count >= 1` |
| `Inventory(stacks)` | Immutable ordered list of stacks | List copied defensively |
| `TravelTx` | Transaction scope, parent/nested, deferred outer-commit actions | Same shape as `inventory.transfer@1`'s `FlowTransaction` |
| `TravelPort` | Port: `traits`, `where`, `inventory`, `exists`, `move` | `move` mutates only inside a `TravelTx`; may throw `PortFailure` |
| `PortFailure` | `RuntimeException` thrown by `move` on unconfirmed failure | May be thrown after `move` has partially acted |
| `TravelReceipt(player, from, to, pose)` | What actually committed | Non-null fields |
| `TravelOutcome` | `Committed` / `Rejected` / `Unsupported` / `Indeterminate` | See §3 |
| `Reason` | `NOT_FOUND`, `TARGET_UNLOADED`, `SAME_POSITION` | See §6 |
| `Trait` | `POSITION_EXACT`, `INVENTORY_PRESERVING` | Declared by a port; checked before mutation |
| `Feedback` | `traveled(receipt)` | Receives a receipt only at the outermost commit |
| `TravelExecutor` | Functional executor interface | `Travel::execute` is the reference |
| `Travel` | Reference executor: `execute`, `drift` | `REQUIRED_TRAITS = {POSITION_EXACT, INVENTORY_PRESERVING}` |

## 3. Outcomes

- **`Committed(receipt)`** — the player is confirmed present at `receipt.to()` (both `exists` and `where`
  agree) before the receipt is produced. `receipt.from()` is the position observed at the start of the call.
- **`Rejected(reason)`** — the request cannot produce a committed travel. Reasons:
  - `NOT_FOUND` — the player does not currently exist in the dimension `where(player)` reports (never seeded,
    or already absent). Checked before `move` is called; no mutation occurs.
  - `SAME_POSITION` — the target is exactly the player's current position; there is nothing to do. Checked
    before `move` is called; no mutation occurs (mirrors the "zero movement is a rejection" law used
    elsewhere in Mycelium — `inventory.transfer@1` L6, `world.copy@1` L7).
  - `TARGET_UNLOADED` — `move` returned without throwing, but arrival could not be confirmed (the target
    dimension/position doesn't yet agree). This is the one `Rejected` reason produced *after* a mutating call:
    see §4 L5 for how state is unwound.
- **`Unsupported(detail)`** — the port lacks a required trait. Checked **before any mutation**, before `where`
  is even called.
- **`Indeterminate(detail)`** — `move` threw `PortFailure`. The outcome is deliberately not `Rejected`: the
  contract does not know whether the move partially applied, so it does not claim state is clean. Never
  collapsed into `Rejected` (L6).

## 4. Laws (safety)

- **L1 Inventory identical** — `port.inventory(player)` before the call equals `port.inventory(player)` after
  it, for every outcome.
- **L2 Position exact on arrival** — for a `Committed` outcome, `port.where(player)` equals the receipt's
  `to()` exactly (no rounding, no snapping).
- **L3 Same-coordinate guarantee for drift** — `Travel.drift`'s target has the same `x`, `y`, `z` (and look)
  as the player's position immediately before the call; only the dimension changes.
- **L4 No receipt until the player exists in the target** — a `Committed` outcome is produced only after
  `port.exists(player, target.dimension())` and `port.where(player).equals(target)` both hold.
- **L5 Finality** — an abort of the outermost transaction restores the player to their pre-call position and
  produces no feedback. Uniquely among Mycelium contracts so far, this law also holds *inside a single call*:
  if `move` returns without throwing but arrival cannot be confirmed (L4's check fails), `Travel.execute`
  aborts its own transaction argument before returning `Rejected(TARGET_UNLOADED)` — a rejection leaves no
  state change behind here exactly as it does everywhere else, even though (unlike every other contract in
  this repository) that requires unwinding a mutation that already happened, because confirmation is only
  possible after the mutating call returns.
- **L6 An exception after move is Indeterminate, never Rejected** — if `move` throws `PortFailure`, the
  outcome is always `Indeterminate`, regardless of whether the failure was total or partial.

## 5. Positive obligations

- **P1** — An admissible travel (existing player, valid required traits, target different from source)
  completes within the call with a `Committed` receipt. A port that declares the required traits but never
  actually lands the player (or lands them somewhere other than the target) fails this obligation — it is
  incorrect, not conservative, exactly as an honest-inspection `NoOp*` fixture is incorrect elsewhere in
  Mycelium.
- **P2 (anti-evasion)** — A port that declares the required traits must not evade P1 by causing `execute` to
  return `Unsupported` or `Rejected` for an admissible request.

Safety alone is satisfiable by a port that never actually moves anyone (every law above is vacuously true of
an unmoved player); P1/P2 are what make that fail.

## 6. Edge semantics

| Condition | Outcome |
|---|---|
| port lacks a required trait | `Unsupported(detail)` — checked first, before `where` is called |
| player not present in the dimension `where` reports | `Rejected(NOT_FOUND)`; no mutation |
| `target.equals(where(player))` | `Rejected(SAME_POSITION)`; no mutation |
| `move` throws `PortFailure` | `Indeterminate(detail)`; never `Rejected` |
| `move` returns normally but `exists`/`where` disagree with `target` afterward | `Rejected(TARGET_UNLOADED)`; the call's own transaction is aborted first, undoing whatever `move` registered |
| `move` returns normally and arrival is confirmed | `Committed(receipt)`; feedback deferred to the outermost commit |

## 7. Traits and admission

Required traits: `POSITION_EXACT`, `INVENTORY_PRESERVING`. A missing trait yields `Unsupported` before any
mutation and before the player's current position is even inspected.

**Basis note (mirrors `flow/SPEC.md` §7):** a trait declaration is a claim, not evidence. The harness does not
trust declarations — it executes fixtures. A failing fixture blocks a claim regardless of declaration, and
every fixture in the rejection matrix declares both required traits. `UnqualifiedTravelPort` (declaring only
`POSITION_EXACT`) exercises the `Unsupported` edge in T11 and is not itself a rejection-matrix entry.

## 8. Fixtures (normative)

Reference fixture set executed by `TravelContractTests`:

| Fixture | Setup | Call | Expectation |
|---|---|---|---|
| T1 positive travel (P1) | player seeded in the overworld with a sample inventory | `execute` to a dream-dimension target | `Committed`; receipt's `to()` equals the target; one feedback event |
| T2 inventory preserved (L1) | same seed | `execute` to the target | inventory before equals inventory after |
| T3 position exact (L2) | same seed | `execute` to the target | `where(player)` equals the target exactly after commit |
| T4 same-position rejected | player already at the target coordinates (`source == target`) | `execute` with `target == source` | `Rejected(SAME_POSITION)`; no feedback |
| T5 not-found rejected | fresh port, player never seeded | `execute` | `Rejected(NOT_FOUND)`; no feedback |
| T6 drift same-coordinate (L3) | player seeded in the overworld | `Travel.drift` to the dream dimension | `Committed`; `x`/`y`/`z` after equal `x`/`y`/`z` before; dimension changed |
| T7 finality on abort (L5) | player seeded; nested tx under an outer that aborts | `execute` inside the nested tx | player back at source; no feedback |
| T8 finality on commit | same as T7, outer commits | — | no feedback before outer commit; player at target and one feedback event after |
| T9 indeterminate on failure (L6) | `UnstablePort` (partially acts, then always throws `PortFailure`) | `execute` | `Indeterminate`, never `Rejected`; no feedback |
| T10 target unloaded before tick (L4/L5) | `LaggyPort` (`move` only takes effect on a later `tick()`) | `execute`, then `tick()` | `Rejected(TARGET_UNLOADED)` before `tick()`; player still at source; no feedback; after `tick()`, player is at the target (sanity check that the delay mechanism itself works) |
| T11 unsupported trait | `UnqualifiedTravelPort` (declares only `POSITION_EXACT`) | `execute` | `Unsupported`; player unmoved |
| T12 anti-evasion (P2) | admissible request against a port declaring both traits | `execute` | must be `Committed`, never `Unsupported`/`Rejected` |

**Rejection matrix** — deliberately incorrect implementations, each of which the harness must reject for the
intended reason:

| Implementation | Violates | Caught by |
|---|---|---|
| `PrematureTravelReceiptExecutor` (emits the receipt/feedback immediately after `move`, using `LaggyPort` whose `move` only takes effect on a later `tick()`) | L4 | `T10:target-unloaded-rejected-before-tick` |
| `InventoryDropPort` (moves correctly, but silently drops the last inventory stack) | L1 | `T2:inventory-unchanged` |
| `OffByOnePort` (always lands the player one block off in x from the requested target) | L2 (via P1: strict arrival verification correctly rejects the mismatch, so the travel never commits) | `T3:position-exact` |
| `SwallowIndeterminateExecutor` (catches `PortFailure` and returns `Rejected` instead of `Indeterminate`) | L6 | `T9:indeterminate-on-failure` |
| `NoOpPort` (declares both traits, honest inspection, never actually moves the player) | P1 | `T1:outcome-committed` |

## 9. Versioning and packaging


## 10. Running the harness

From `contracts/dimension-travel`:

```powershell
$h = Get-ChildItem -Recurse -Filter *.java ..\..\harness\src | % FullName
$s = Get-ChildItem -Recurse -Filter *.java src | % FullName
& "$env:JAVA_HOME\bin\javac.exe" --release 17 -d out ($h + $s)
& "$env:JAVA_HOME\bin\java.exe" -cp out mycelium.dimension_travel.v1.harness.Run
```

Or from the Mycelium root: `./run-contracts.sh dimension-travel`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 11. Explicit non-claims

- No Minecraft, Fabric, NeoForge, or Osmosis code was executed to produce this package.
- The native reference adapter (`FabricDimensions.teleport`, vanilla `teleportTo`/`changeDimension`) and a
  packaged-game fixture are later artifacts; until they exist, this contract has no game-target evidence. In
  particular, "target chunk not loaded" is modeled here only as `LaggyPort`'s deferred `move` — no real chunk
  loading, netcode, or client-server round trip is simulated.
- `Pose` is set by `move` but not independently queryable on `TravelPort` in v1; no law here verifies arrival
  pose beyond what the receipt records. A future revision may add a query method and a pose-exactness law.
- Concurrent travel requests for the same player, and the interaction between an in-flight `LaggyPort`-style
  deferred move and a second travel request issued before it resolves, are out of scope for v1 and named here
  rather than silently omitted.
