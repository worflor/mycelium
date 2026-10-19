# world.replay@1

Resuming one operation's run-length encoded block replay under a per-call work budget. WorldPrompt uses it to preserve ordered placement across server ticks.

## Model

`ReplayProgram` owns one operation's immutable triples `(start, length, paletteIndex)`. It does not own the palette, Minecraft, or the meaning of a write. Its constructor validates and privately copies the triples; consumers can prepare a program off the server thread. Each `cursor()` call returns fresh `ReplayTraversal` state sharing only that immutable data. A caller supplies `PlacementPort` callbacks for whole-run skips and individual visits.

The program receives the volume and palette size established by payload validation. It rejects incomplete triples, non-positive lengths, unordered or overlapping runs, out-of-volume ranges, and palette indexes out of range. Gaps are allowed. The run count cannot exceed the volume, so its owned copy is bounded by operation positions. An empty run array is a valid empty operation. O(1) cursor construction is a property of the reference implementation, not a universal performance guarantee.

## Laws

| Law | Statement |
|---|---|
| R1 | Each successful `visit` advances exactly one position, and successful visits occur in ascending run order across any sequence of drains. A callback that throws may be offered the same position again. |
| R2 | A drain uses at most its budget. Every `visit` costs one unit, and every successful whole-run skip costs one unit. |
| R3 | When `skipRun(paletteIndex)` returns true, the cursor visits none of the remaining positions in that run, counts all of them in `skippedRunPositions`, and uses exactly one work unit. |
| R4 | If a callback throws, its current cell or run does not advance. Earlier progress and skipped-run counts remain available, and retry begins at the failed cell or run. |
| R5 | Malformed run data is rejected before any callback can observe it. |
| R6 | The program owns a copy of input data and never exposes its array; later caller mutation cannot change the replay. |
| R7 | Each `ReplaySource.cursor()` call returns fresh independent traversal state over the same immutable program. |

`visit` always costs one unit, even when the native adapter decides that a position does not match or produces no world write. Those outcomes belong to the adapter; they must not change cursor order or budgeting. A cursor that never visits an admissible non-skipped run violates R1. The cursor guarantees retry position, not exactly-once external effects: a port must make its effects retry-safe or atomic if it can mutate the world and then throw.

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `ReorderedCursor` | Visits positions of a run in reverse order | R1:ordered-visits |
| `NoOpReplayCursor` | Completes without visiting any admissible position | R1:ordered-visits |
| `BudgetIgnoringCursor` | Visits a whole run even when the budget allows one unit | R2:work-within-budget |
| `PerCellSkipCursor` | Charges one unit per skipped position instead of one per remaining run | R3:whole-run-skip-costs-one |
| `AdvanceOnFailureCursor` | Advances past the cell whose native visit threw | R4:failed-visit-is-retried |
| `TrustsMalformedDataCursor` | Accepts an out-of-volume run | R5:malformed-run-rejected |
| `AliasingCursor` | Reads later caller mutations to its input | R6:input-is-copied |
| `SharedPositionReplaySource` | Returns the same mutable cursor from multiple `cursor()` calls | R7:independent-cursors |

## Using it

Construct a `ReplayProgram` from an operation's validated runs, volume and palette size during payload preparation, which may run off the server thread. Keep the immutable program with the parsed operation. On each server step, call `program.cursor()` once when beginning that operation and call `drain(budget, port)` on that cursor. When it reports complete, release the cursor and let the mod handle operation completion, delay and feedback. A rebuild can make another cursor from the same program. `skipRun` should return true only when the adapter can leave the entire rest of that run untouched in one unit (for example, a non-random run whose palette entry could not be parsed). Otherwise return false and let `visit` handle each position. A callback that throws leaves its current position for the next drain.

`Drain.skippedRunPositions` is the delta for that successful call. `ReplayTraversal.skippedRunPositions()` is cumulative, including completed skip work before a later callback throws. An adapter should account for that cumulative delta in a `finally` block around `drain`, then keep its own counts for per-cell native rejections. It must not count match misses unless its existing behavior does so.

The program and cursor own only RLE traversal and budgeting. Palette parsing, dynamic match rules, random choices, property inheritance, block writes, write warnings, sounds, operation ordering, delay, undo and rebuild remain the consumer's responsibility.

## Running the harness

From the Mycelium root, `bash run-contracts.sh world-replay` (or `./run-contracts.ps1 world-replay`), or `./gradlew myceliumVerify -Pmycelium.contracts=world.replay@1`. Expected: the reference clean, every matrix entry `CAUGHT`, exit code 0.

## Explicit non-claims

The contract proves the program's immutable input, cursor independence, ordering, resume position, and work accounting only. Its deterministic seeded oracle compares 32 bounded RLE cases and multiple budgets against R1 and R2. It does not prove a particular runtime cost, that a Minecraft block write succeeds, that a chunk is loaded, or that the caller's budget corresponds to a time limit. The native adapter and consumer game tests establish those facts.
