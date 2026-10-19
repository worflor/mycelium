# inventory.transfer@1 — Contract Specification

| | |
|---|---|
| Contract id | `inventory.transfer@1` (implementation namespace `mycelium.flow.v1`) |
| Status | First contract package of the Mycelium founding design (see `../../DESIGN.md`, v0.4, §6–§8, §11, §14) |
| Domain | Bounded, single-variant transfer between disjoint, non-voiding providers whose required guarantees are established |
| Reference model | `src/` — pure Java 17, no Minecraft or loader imports, no dependencies |
| Claims discipline | Everything in this package is design-time. No claim here is evidence of behaviour on a real game target; that requires the native adapter and packaged-game fixtures of Gate B/C |

---

## 1. Scope

- One resource variant per operation. Aggregate quantities only; slot layout and fragmentation are the provider's concern.
- No transformation, merging of distinct identities, or voiding of the transferred resource.
- Providers are **disjoint** by domain statement. Aliasing detection is deferred to a later contract revision or a runtime applicability check (DESIGN §14 counterexamples).
- Providers participate in **rollback** so that aborting the owning transaction restores prior state.
- Inspection and execution happen within one call. The staleness window is closed **by construction** in v1; split plan/execute APIs, where `Stale` is exercised, arrive with later revisions. `Stale` exists in the outcome set now so the vocabulary is stable.

## 2. Types

| Type | Meaning | Invariants |
|---|---|---|
| `ResourceKey` | Opaque identity token for a resource variant | Non-blank token; equality is identity |
| `FlowBackend` | A provider: traits, availability, admissible capacity, extract, insert | Mutation only inside a transaction; every mutation registers an undo |
| `FlowTransaction` | Transaction scope with parent/nested semantics and deferred outer-commit actions | Undo runs in reverse order on abort; nested commits remain cancellable by the parent |
| `Receipt` | What actually committed | `1 <= committed <= requested`; zero movement is not a receipt |
| `TransferOutcome` | `Committed` / `Rejected` / `Unsupported` / `Stale` | See §3 |
| `Feedback` | Presentation channel | Receives a receipt only at the outermost commit |

## 3. Outcomes

- **`Committed(receipt)`** — the outermost transaction committed and exactly `receipt.committed()` units moved. `committed` is always ≥ 1.
- **`Rejected(reason)`** — the request cannot produce a committed transfer. Enumerated reasons: `ZERO_REQUEST`, `EMPTY_SOURCE`, `FULL_DESTINATION`, `EXTRACTION_FAILED`, `INSERTION_FAILED`, `TRANSFER_FAILED`. Rejection implies **no state change survives**.
- **`Unsupported(detail)`** — a provider lacks a required trait. Checked **before any mutation**.
- **`Stale`** — reserved for split-plan executions (v1: not producible; see §1).

## 4. Laws (safety)

- **L1 Conservation** — after the outermost commit: source decreased by exactly `committed`, destination increased by exactly `committed`.
- **L2 Bounds** — `0 < committed <= min(requested, available(source), admissibleCapacity(destination))`.
- **L3 Identity** — only the requested resource changes. Every other resource amount on both providers is unchanged after the commit.
- **L4 No partial application outside a receipt** — a rejected or aborted operation leaves no state change; a committed operation changes exactly what the receipt states.
- **L5 Finality** — no success feedback before the outermost commit. If the outermost transaction aborts, no state change and no success feedback survive; receipts produced under a parent transaction are provisional.
- **L6 Zero movement is a rejection** — a committed receipt always reports ≥ 1 unit. A provider that cannot move anything must be rejected with a reason, never reported as a successful transfer of zero.

## 5. Positive obligations

- **P1** — Under the stated fixture assumptions (stable, disjoint, non-voiding, established guarantees, no concurrent mutation): `committed == min(requested, available, admissible capacity)`. **Returning less is incorrect, not conservative.**
- **P2 (anti-evasion)** — A provider that declares the required traits must not evade P1 by returning `Unsupported` or `Rejected` for an admissible request. Evasion is a positive-obligation violation.

Safety alone is satisfiable by doing nothing; P1/P2 are what make a no-op fail.

## 6. Edge semantics

| Condition | Outcome |
|---|---|
| `requested <= 0` | `Rejected(ZERO_REQUEST)` |
| `available == 0` | `Rejected(EMPTY_SOURCE)` |
| `admissible capacity == 0` | `Rejected(FULL_DESTINATION)` |
| `min(...) > 0` but less than requested | `Committed(min(...))` — partial capacity is a committed transfer, not a rejection |
| provider insert admits less than extracted | remainder restored to source; committed = what the destination accepted (T11) |
| provider insert admits nothing of what was extracted | everything restored; `Rejected(TRANSFER_FAILED)` (T11) |
| source will not take the remainder back | the executor throws: the outcome is unconfirmed, and an unconfirmed outcome is never an ordinary `Rejected` (DESIGN §7). The caller aborts; nothing survives (T12) |
| source reports stock but extraction yields nothing | `Rejected(EXTRACTION_FAILED)`: the source refused, not the destination; no change (T13) |

## 7. Traits and admission

Required traits for this contract: `ROLLBACK_PARTICIPATION`, `IDENTITY_PRESERVING`. A missing trait yields `Unsupported` before any mutation.

**Basis note (DESIGN §8.1):** a trait declaration is a claim, not evidence. The harness does not trust declarations — it executes fixtures. A failing fixture blocks a claim regardless of declaration, and all fixtures in the rejection matrix declare the required traits. Qualification records remain defeasible by counterevidence.

## 8. Fixtures (normative)

Reference fixture set executed by `FlowContractTests`:

| Fixture | Setup | Request | Expectation |
|---|---|---|---|
| T1 positive | source 20, destination capacity 7, empty | 12 | `Committed(7)`; source 13, destination 7; one feedback at commit |
| T2 zero request | source 20 | 0 | `Rejected(ZERO_REQUEST)`; no change; no feedback |
| T3 empty source | source 0, destination capacity 7 | 12 | `Rejected(EMPTY_SOURCE)`; no change |
| T4 full destination | source 20, destination capacity 7 holding 7 | 12 | `Rejected(FULL_DESTINATION)`; no change |
| T5 partial capacity | source 20, destination capacity 3 | 12 | `Committed(3)`; source 17, destination 3 |
| T6 finality on abort | source 20, destination capacity 7; nested under an outer transaction that aborts | 12 | state restored to 20 / 0; **no success feedback** |
| T7 finality on commit | same, outer commits | 12 | source 13, destination 7; exactly one feedback, after the outer commit |
| T8 identity | source 20 iron + 5 gold; destination capacity 64 holding 2 gold | 12 iron | destination iron 12, destination gold 2, source gold 5 |
| T9 unqualified | providers lacking a required trait | 12 | `Unsupported`; no mutation |
| T10 anti-evasion | admissible request against providers declaring the required traits | 12 | must be `Committed`, never `Unsupported`/`Rejected` |
| T11 admits less than promised | source 20; destination promises 7, holds 4 (`OverpromisingBackend`); then one that promises 7, holds 0 | 12 | `Committed(4)`; source 16, destination 4; one feedback of 4. Holding 0: `Rejected(TRANSFER_FAILED)`, source 20, no feedback |
| T12 unrestorable remainder | source takes nothing back (`OneWaySourceBackend`); destination promises 7, holds 4 | 12 | the executor throws; after the caller's abort, source 20, destination 0, no feedback |
| T13 source refuses | source reports 20, gives none (`StuckSourceBackend`) | 12 | `Rejected(EXTRACTION_FAILED)`; no change |

`OverpromisingBackend`, `OneWaySourceBackend` and `StuckSourceBackend` are not broken implementations of this contract. They are provider behaviours every executor must handle (DESIGN §8.1: a promise of room is not room). Without them the executor's restore path was never reached: deleting it from the reference passed every fixture until T11 existed (red team, 2026-09-26).

**Rejection matrix** — deliberately incorrect implementations, each of which the harness must reject for the intended reason:

| Implementation | Violates | Caught by |
|---|---|---|
| `NoOpBackend` (never mutates, honest inspection) | P1 / P2 | T1 outcome, T10 anti-evasion |
| `UndercountBackend` (moves one less than requested) | P1 | T1 committed quantity |
| `VoidingPartialBackend` (reports acceptance, retains half) | L1 / L4 | T1 destination quantity |
| `IdentityMergerBackend` (merges distinct resources into one bucket) | L3 | T8 identity |
| `PrematureFinalizerExecutor` (emits feedback at execution, not outermost commit) | L5 | T6 finality on abort |
| `NoRestoreExecutor` (drops the remainder a destination did not admit) | L1 / L4 | T11 source restored |
| `UncheckedExtractionExecutor` (does not look at what extraction gave, so reports the source's refusal as the transfer's) | §6 | T13 extraction failed |

## 9. Versioning and packaging

- This is `inventory.transfer@1`. Changes to laws, outcomes, or edge semantics produce `@2`; additions that preserve all laws may remain `@1`.
- The implementation namespace here (`mycelium.flow.v1`) is development-time. Emitted artifacts renamespace Mycelium-owned classes under the consuming mod's artifact-owned prefix (DESIGN §12 packaging rule); no shared Mycelium runtime ABI is assumed.

## 10. Running the harness

From the Mycelium root: `bash run-contracts.sh flow` (or `.\run-contracts.ps1 flow`), or `./gradlew myceliumVerify -Pmycelium.contracts=inventory.transfer@1`. The harness runs on the shared `mycelium.harness.ContractRun`, like every package.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 11. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package.
- The native reference adapter and packaged-game fixture are the next artifacts (Gate B/C); until they exist, this contract has no game-target evidence.
- Aliasing, concurrency, and split plan/execute staleness are out of scope for v1 and named here rather than silently omitted.
