# dimension.travel@2 — Contract Specification

| | |
|---|---|
| Contract id | `dimension.travel@2` (implementation namespace `mycelium.dimension_travel.v2`) |
| Supersedes | `dimension.travel@1` (every `@1` type and the `Travel` executor are carried over unchanged) |
| Domain | A trip away and back that survives the process dying in between |
| Reference model | `src/` — pure Java 17, no Minecraft or loader imports, no dependencies |
| Promoted from | Consumer crash-recovery requirements (`docs/decisions/0002-what-somnium-taught.md`) |
| Claims discipline | Design-time only. Whether a provider's journal really survives a real server crash is that provider's gametest evidence |

---

## 1. Scope

`@1` moves a player and confirms arrival. It says nothing about coming back, and that is where every "go
somewhere and come back" mod (a dream, a pocket dimension, an arena) loses things: the process dies while the
player is away, the player's inventory is saved with them, and whatever ledger the mod kept in memory is gone.
`@2` adds a **trip**: journaled before the move, struck on the way home, recovered at the next session if the
process died in between. The consumer keeps its own ledger; the contract guarantees it is asked to settle a lost
trip exactly once, at the right time.

## 2. Types (new in `@2`)

| Type | Meaning | Invariants |
|---|---|---|
| `Journal(player, origin, carried)` | An open trip: who left, from where, carrying what | All non-null; written before the move |
| `Settle` | `void settle(Journal journal, Inventory now)`: the consumer's ledger, asked once | Called only after the outermost commit |
| `RecoverReceipt(player, journal, broughtHome)` | A lost trip settled | `broughtHome` is false when the traveller was already at the origin |
| `RecoverOutcome` | `Recovered` / `Rejected` / `Unsupported` / `Indeterminate` | See §3 |
| `TripExecutor` | `depart`, `returnHome`, `recover` | `Trip.REFERENCE` is the reference |
| `TravelPort` (extended) | `journal(player)`, `writeJournal(player, journal, tx)`, `strikeJournal(player, tx)` | The journal is durable (survives a restart); both mutators register an undo |
| `Reason` (extended) | `TRIP_OPEN`, `NO_TRIP` | |
| `Trait` (extended) | `JOURNALED` | Required by every trip verb |

## 3. Outcomes

- `depart` and `returnHome` return `@1`'s `TravelOutcome`. A failed move is `Indeterminate` (never collapsed
  into `Rejected`), and for `depart` that is exactly the case the journal is for: it stays.
- `recover` returns `Recovered(receipt)`; `Rejected(NO_TRIP)` when nothing is open; `Unsupported` without
  `JOURNALED`; `Indeterminate` when the way home failed (the journal stays open for the next session).

## 4. Laws (safety) and obligations

- **L1 (journal before move)** — `depart` writes the journal before `move` runs (`T1:journal-before-move`).
- **L2 (one open trip)** — `depart` with an open journal is `Rejected(TRIP_OPEN)`; nothing moves and the origin
  is not rewritten (`T2`).
- **L3 (the journal survives a crash)** — the journal lives in the port's durable store; after a failed move and a
  restart (no commit, no abort, no memory) the next process sees the trip (`T3`).
- **L4 (a rejection leaves nothing)** — an arrival that can't be confirmed (`TARGET_UNLOADED`) aborts the journal
  with the move (`T4`).
- **L5 (home strikes the trip)** — `returnHome` moves to the journal's origin and strikes it in the same
  transaction; nothing is left to recover (`T5`).
- **L6 (recover settles exactly once)** — an open journal with the traveller away: `recover` brings them to the
  origin, strikes the journal, and calls `settle` exactly once with the journal and what they have now; a second
  `recover` is `Rejected(NO_TRIP)` and settles nothing (`T6`, `T8`).
- **L7 (settle is feedback)** — `settle` runs only after the outermost commit; an aborted recovery settles nothing
  and leaves the trip open (`T7`).
- **L8 (already home)** — the traveller is at the origin (the process died after the move home, before the strike):
  `recover` strikes and settles nothing (`T9`).
- **P1** — a port that claims `JOURNALED` keeps a journal (`T1:journal-written`); a qualified, admissible departure
  commits and may not hide behind a refusal (`T11`).
- **Admission** — without `JOURNALED` every verb is `Unsupported` and nothing changes (`T10`).

## 5. Fixtures and rejection matrix

`T1`..`T11` in `harness/TripContractTests.java`. The reference port is `MemoryTravelPort` over a `Store`;
`restart()` is a new process over the same store (how a crash is simulated).

| Implementation | Violates | Caught by |
|---|---|---|
| `LateJournalExecutor` (moves, then journals) | L1 | `T1:journal-before-move` |
| `NoOpJournalPort` (claims `JOURNALED`, keeps nothing) | P1 | `T1:journal-written` |
| `OverwritingDepartExecutor` (departs over an open trip) | L2 | `T2:second-departure-rejected` |
| `ForgetfulJournalPort` (journal in memory, not the store) | L3 | `T3:journal-survives-crash` |
| `NeverStrikePort` (never strikes) | L5 | `T5:journal-struck-on-return` |
| `SilentRecoverExecutor` (brings home, never settles) | L6 | `T6:settled-once` |
| `EagerSettleExecutor` (settles before the commit) | L7 | `T7:no-settle-before-commit` |

## 6. Versioning

`@2` is a new major in its own directory (`contracts/dimension-travel-2/`, package `mycelium.dimension_travel.v2`),
so `@1` consumers are untouched. The journaled-trip model was first adopted by Somnium in September 2026.

## 7. Explicit non-claims

- That any real provider's store survives a real crash: that is the provider's evidence (a gametest can only
  simulate the loss of memory, in a consumer crash-recovery test).
- What a settled trip should keep: that is the consumer's rule, handed to it through `Settle`.
