# persistence.player-state@1 — Contract Specification

| | |
|---|---|
| Contract id | `persistence.player-state@1` (implementation namespace `mycelium.player_state.v1`) |
| Status | Mycelium contract package, sibling to `inventory.transfer@1` (see `../flow/SPEC.md`, `../../CONTRACT-PACKAGE.md`) |
| Domain | Durable per-player key/value state that must survive the ordinary lifecycle of a running server, versioned by an explicit schema and migrated by a declared, walk-verified chain |
| Reference model | `src/` — pure Java 17, no Minecraft or loader imports, no dependencies |
| Claims discipline | Everything in this package is design-time. No claim here is evidence of behavior on a real game target; that requires a native adapter and packaged-game fixtures of a later gate |

---

## 1. Scope

- One player, one logical state blob: a schema tag plus a flat string/string field map. Structure inside the map (nesting, typed values) is the caller's concern.
- Storage durability across the ordinary in-session lifecycle (death/respawn, dimension change, relog, restart) is a contract requirement, not an implementation detail.
- Schema migration is single-step, chained: a `Migration` moves a blob from exactly one schema to exactly one later schema. A `MigrationChain` composes steps; whether a chain is total from one schema to another is a real property of its registered steps, verified by walking them, never trusted from a self-reported claim.
- Finality follows the `Tx` transaction pattern (mirrors `flow`'s `FlowTransaction`): a write registers an undo and defers feedback to the outermost commit.
- Out of scope: concurrent writers to the same player, cross-player aggregation, and the storage medium itself (files, NBT, a database) — those belong to the native adapter.

## 2. Types

| Type | Meaning | Invariants |
|---|---|---|
| `PlayerId` | Opaque identity token for a player | Non-blank token; equality is identity |
| `StateBlob` | `(schema, fields)` snapshot | `fields` is defensively copied to an immutable map at construction |
| `Tx` | Transaction scope with parent/nested semantics and deferred outer-commit actions | Same shape as `flow.v1.model.FlowTransaction`: undo runs in reverse on abort; nested commits stay cancellable by the parent |
| `StatePort` | A storage provider: `read`, `write`, declared `traits()` | Mutation only inside a `Tx`; every mutation registers an undo |
| `Trait` | `DURABLE`, `RESPAWN_SAFE` | A declaration, not evidence (§7) |
| `Migration` | One step, `from` schema to `to` schema | `migrate` must strictly increase the schema (`to() > from()`) |
| `MigrationChain` | Ordered set of steps | `total(from, to)` reports whether every step from..to is present and contiguous; `upgrade(blob, target)` always performs the real walk regardless of what `total` claims, and is idempotent when `blob.schema() == target` |
| `StateReceipt` | `(player, schema)` — what actually committed | |
| `StateOutcome` | `Committed` / `Rejected` / `Unsupported` | See §3 |
| `Feedback` | Presentation channel | Receives a receipt only at the outermost commit |

## 3. Outcomes

- **`Committed(receipt)`** — the write (or upgrade) succeeded; `receipt.schema()` names the resulting schema.
- **`Rejected(reason)`** — the request cannot succeed as stated. Enumerated reasons: `SCHEMA_GAP`, `EMPTY_BLOB`. Rejection implies no state change survives.
- **`Unsupported(detail)`** — the port lacks a required trait. Checked before any mutation.

## 4. Laws (safety)

- **L1 Round-trip equality** — a written blob, once committed, reads back identical (schema and every field) to what was written.
- **L2 Lifecycle survival** — a committed blob survives, unchanged, a simulated death/respawn, dimension change, relog, and full server restart.
- **L3 Schema-versioned reads** — reading an older-schema blob through a *total* chain yields the current schema with every migration applied; the result is exactly what the chain's steps produce, nothing improvised.
- **L4 Gap safety** — a chain with a missing step never produces a partial upgrade. It yields `Rejected(SCHEMA_GAP)` (or, for a bare read, an absent result) — even if the chain's own `total()` wrongly claims completeness. The executor never trusts the declaration; it performs the real walk and lets a missing step surface as a gap.
- **L5 Finality via transaction** — no feedback before the outermost commit; an aborted write leaves the previous blob (or absence) exactly as it was, and produces no feedback.

## 5. Positive obligations

- **P1** — Under the stated fixture assumptions (a durable, respawn-safe port, no concurrent mutation): a written blob is readable, byte-for-byte identical, after every simulated lifecycle event in any order. A port that quietly drops data — on any single event or in general — is incorrect, not conservative.

Safety alone is satisfiable by doing nothing (a `NoOp` port that never persists still reports no errors); P1 is what makes a no-op fail, per `CONTRACT-PACKAGE.md` rule 3.

## 6. Edge semantics

| Condition | Outcome |
|---|---|
| `blob.fields()` is empty | `Rejected(EMPTY_BLOB)` before any mutation |
| port lacks `DURABLE` | `Unsupported` before any mutation |
| `blob.schema() == target` in an upgrade | `Committed` with the blob returned unchanged (idempotent) |
| chain has every step from..target | `Committed`; blob migrated through every intermediate schema |
| chain is missing a step anywhere on the from..target path | `Rejected(SCHEMA_GAP)`; no partial result, regardless of what the chain's `total()` reports |
| read against a `PlayerId` with nothing stored | empty result, not an error |

## 7. Traits and admission

Required trait for durable persistence: `DURABLE`. `RESPAWN_SAFE` is advertised by the reference port but not required by the executor — `L2` is exercised directly by fixture-simulated lifecycle events regardless of the trait's presence, because a trait declaration is a claim, not evidence (mirrors `flow`'s DESIGN §8.1 basis note). The harness does not trust `MigrationChain.total()` either, for the identical reason: `SkippingMigrationChain` in the rejection matrix declares totality it does not have, and the correct executor still catches the gap because it walks the real steps (§4 L4).

## 8. Fixtures (normative)

Reference fixture set executed by `PlayerStateContractTests`:

| Fixture | Covers | Expectation |
|---|---|---|
| T1 positive | round-trip write/read; `EMPTY_BLOB` rejection; `Unsupported` trait check | write then read equal; empty-field write rejected without mutation; untrusted port rejected without mutation |
| T2 death/respawn | L2 | blob unchanged after `simulateDeathRespawn` |
| T3 dimension change | L2 | blob unchanged after `simulateDimensionChange` |
| T4 relog | L2 | blob unchanged after `simulateRelog` |
| T5 restart | L2 | blob unchanged after `simulateRestart` |
| T6 schema-versioned upgrade | L3 | a total 1→2 chain upgrades a stored v1 blob to v2 with the migrated field present and the original preserved |
| T7 schema gap | L4 | a chain missing the 2→3 step rejects `upgrade` with `SCHEMA_GAP`, and `readCurrent` returns empty rather than a partial blob |
| T8 finality via `Tx` | L5 | an aborted nested write restores the previous blob with no feedback; a committed one applies the update with exactly one feedback event, only after the outer commit |
| T9 seeded property fixture | P1 | 30 seeded players, random field maps, each survives all four simulated lifecycle events in sequence with an identical read back |

**Rejection matrix** — deliberately incorrect implementations, each of which the harness must reject for the intended reason:

| Implementation | Violates | Caught by |
|---|---|---|
| `LossyStatePort` (drops one field on every write) | L1 | T1:round-trip-equal |
| `RespawnResetPort` (clears the player's data on death/respawn) | L2 / P1 | T2:survives-death-respawn |
| `RestartAmnesiaPort` (clears the whole store on restart) | L2 / P1 | T5:survives-restart |
| `NoOpPort` (write is ignored entirely) | P1 (anti-evasion) | T9:property-0 |
| `SkippingMigrationChain` (declares `total()` true while genuinely missing a step) paired with a naive executor that trusts the declaration instead of walking | L4 | T7:schema-gap-rejected |

The `SkippingMigrationChain` entry exercises the naive failure mode directly (`fixtures.NaiveMigrationUpgrade`, a stand-in executor that trusts `total()` instead of walking): it demonstrates what would go wrong if the real `PlayerState.upgrade`/`MigrationChain.upgrade` ever took a chain's self-report at face value, which they do not.

## 9. Versioning and packaging

- This is `persistence.player-state@1`. Changes to laws, outcomes, or edge semantics produce `@2`; additions that preserve all laws may remain `@1`.
- The implementation namespace here (`mycelium.player_state.v1`) is development-time. Emitted artifacts renamespace Mycelium-owned classes under the consuming mod's artifact-owned prefix; no shared Mycelium runtime ABI is assumed.

## 10. Running the harness

From `contracts/player-state`:

```powershell
$h = Get-ChildItem -Recurse -Filter *.java ..\..\harness\src | % FullName
$s = Get-ChildItem -Recurse -Filter *.java src | % FullName
& "$env:JAVA_HOME\bin\javac.exe" --release 17 -d out ($h + $s)
& "$env:JAVA_HOME\bin\java.exe" -cp out mycelium.player_state.v1.harness.Run
```

Or, from the Mycelium root: `./run-contracts.sh player-state`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 11. Explicit non-claims

- No Minecraft, Fabric, NeoForge, or Osmosis code was executed to produce this package.
- No native storage medium (NBT, a save file, a database) was exercised; `MemoryStatePort` and its lifecycle simulations are an in-memory stand-in for "survives the session," not evidence about any specific serialization format.
- Concurrent writers, cross-player aggregation, and multi-step migrations authored out of order are out of scope for v1 and named here rather than silently omitted.
