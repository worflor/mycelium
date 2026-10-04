# chat.coverage@1

Tracking whether an owned public-chat stream is complete at explicitly acknowledged storage boundaries. A host can
miss a line when opening or writing its fingerprint ledger fails, when a line exceeds a cap, or when a fallback path
silently drops it. A later diagnostic must not treat that partial history as healthy. This contract models the coverage
claim independently from `chat.invocation@1`, which decides whether a line is an invocation and what context it carries.

Written for Recon after a public-chat coverage ledger could silently lose a line and later appear healthy. The law is
general to any consumer that keeps an ordered public event ledger.

## Model

- `Snapshot(runId, committedThrough, status, cause)`: immutable scalar state for one caller-owned run. `runId` is a
  nonblank opaque string of at most 128 characters; `committedThrough` is nonnegative. A complete snapshot has cause
  `NONE`; an incomplete snapshot has one concrete cause.
- `Coverage`: pure transitions over snapshots. `begin(runId)` is the caller's explicit assertion that ownership began
  before sequence 1; it yields a complete empty run at sequence 0. `unknownStart(runId)` represents late or uncertain
  ownership and is incomplete. `commitDurable(snapshot, sequence)` is called only after the caller confirms the
  sequence's durable boundary. `markIncomplete(snapshot, cause)` records a loss. `restore(snapshot)` validates and
  copies every field; it never upgrades a state.
- `Status`: `COMPLETE` or `INCOMPLETE`.
- `Cause`: `NONE`, `UNKNOWN_START`, `OPEN_FAILED`, `INITIAL_WRITE_FAILED`, `APPEND_FAILED`, `FLUSH_FAILED`,
  `CLOSE_FAILED`, `OVERSIZE_PUBLIC_LINE`, `CAPACITY_REACHED`, `PUBLIC_FALLBACK`, `SEQUENCE_GAP`,
  `INVALID_SEQUENCE`, or `REOPEN_UNVERIFIED`.
- `CoverageLedger`: the reference implementation shipped in `model/`.

The caller supplies sequence numbers and confirms durability before calling `commitDurable`. The model does not read a
clock, environment, filesystem, chat source, or platform API. It cannot prove that a caller's durability claim is true.
An incomplete run cannot be made complete by a later successful append or restore. The first loss cause is retained.
To start a genuinely new run, the caller supplies a new run identity; `begin` is never a reopen operation.

## Laws

| Law | Label | Statement |
|---|---|---|
| L1 | `L1:established-empty-run-is-complete` | Explicit ownership before sequence 1 yields a complete snapshot at sequence 0, even when no event is committed |
| L2 | `L2:unknown-start-is-incomplete` | Late or unknown initial ownership cannot be represented as complete |
| L3 | `L3:only-next-sequence-advances` | A durable commit advances exactly from `n` to `n+1`; a gap, duplicate, invalid sequence or overflow does not advance and makes coverage incomplete |
| L4 | `L4:loss-is-sticky` | Any explicit loss cause makes a complete run incomplete; later commits cannot heal it |
| L5 | `L5:restore-never-heals` | Restoring an incomplete snapshot preserves its run, sequence, status and first cause |
| L6 | `L6:first-loss-cause-is-stable` | Subsequent loss reports do not replace the first cause |
| L7 | `L7:snapshot-is-immutable` | Transitions leave their input snapshot unchanged and return state with the required run and sequence |

## Positive obligation

| Obligation | Label | Statement |
|---|---|---|
| P1 | `P1:durable-next-event-is-recorded` | A complete run commits its next sequence as complete and advances the recorded sequence exactly once |

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `NoOpCoverage` | Never records a durable next event | `P1:durable-next-event-is-recorded` |
| `IncompleteEmptyCoverage` | Calls a newly established empty run incomplete | `L1:established-empty-run-is-complete` |
| `CompleteUnknownCoverage` | Treats late ownership as complete | `L2:unknown-start-is-incomplete` |
| `GapAcceptingCoverage` | Advances across a skipped sequence as healthy | `L3:only-next-sequence-advances` |
| `SilentLossCoverage` | Ignores a failed boundary and remains healthy | `L4:loss-is-sticky` |
| `LaunderingRestoreCoverage` | Reopens an incomplete snapshot as complete | `L5:restore-never-heals` |
| `ReplacingCauseCoverage` | Replaces the first loss cause with a later one | `L6:first-loss-cause-is-stable` |
| `ResettingTransitionCoverage` | Rewrites a transition's run or prior sequence | `L7:snapshot-is-immutable` |

## Using it

Create a new complete run only when the consumer has established ownership from its first sequence. If it starts late,
cannot open the ledger, cannot durably write or flush a boundary, reaches a cap, or uses a path that may omit public
events, mark the run incomplete with the corresponding cause. Call `commitDurable` only after the consumer's own
durability check succeeds. Persist the scalar snapshot as consumer-owned state; on reopen, reconstruct it through
`restore`. Missing or untrusted persisted state uses `unknownStart`, never `begin` for the same run.

This contract does not prove that the source emitted every public event, that a consumer observes the source at the
right time, or that a filesystem/API's reported durable boundary survives a real crash. Those require independent
consumer evidence.

## Harness

From the Mycelium root, `bash run-contracts.sh chat-coverage` (or `.\run-contracts.ps1 chat-coverage`), or
`./gradlew myceliumVerify -Pmycelium.contracts=chat.coverage@1`. Expected: the reference clean, every matrix entry
`CAUGHT`, exit code 0.
