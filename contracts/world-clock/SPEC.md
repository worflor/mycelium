# world.clock@1

Time the way 26.1 models it, on every version. 26.1 replaced the one `dayTime` with **world clocks**: a clock is a
server-global state (ticks, a fractional carry, a rate, a pause), and meaning lives in **timelines** laid over a clock (a
period, named markers). The game before 26.1 (1.21.1 and 1.20.1 alike) is the special case: one clock at rate one, a
24000-tick day, a 192000-tick moon. So this contract models the general thing, and the old game is a law (C4), not a
second code path.


## Model

All in `model/`, plain Java, no Minecraft.

- `ClockId`, `ClockState(totalTicks, partialTick, rate, paused)`: a clock's identity and state. The domain is the states
  the game reaches: non-negative ticks, a fraction in [0, 1), a rate in [0, 1e9] (the game has no ceiling; past an int's
  range its floor saturates).
- `Timeline.on(clock, id, periodTicks).marker(id, ticks)...build()`, `Marker(id, clock, ticks, periodTicks,
  showInCommands)`: a period (none: the timeline is the clock's total) and markers inside it. A marker takes its period
  from its timeline; a periodic marker lies in `[0, period)` (the game refuses otherwise); a marker id is unique in a
  timeline and, in a `ClockWorld`, on a clock.
- `ClockModel`: the arithmetic, one method per rule, every one a pure function. `tick`, `set`, `add`, `pause`, `rate`,
  `moveTo`, `sleep`; `occursAt`, `resolveTimeToMoveTo`, `repetitionCount`; `periodCount`, `currentTicks`, `phase`;
  `network`, `extrapolate`. Derived and not restated by a variant: `apply` (a `ClockCommand`), `edgesBetween`,
  `previousOccurrence`, `nextOccurrence`. `ReferenceClockModel` is the reference.
- `ClockCommand` (`SetTicks`, `AddTicks`, `Pause`, `Resume`, `SetRate`, `MoveToMarker`), `Move(result, state)` with
  `MoveResult` (`NO_SUCH_MARKER`, `NOT_MOVED`, `MOVED`), `Applied(state, move?)`.
- `ClockWorld`: the declaration a mod writes once: clocks, timelines, and each dimension's default clock (the Nether has
  none). `ClockWorld.vanilla()` is 26.3's: the overworld and end clocks, `minecraft:day` and `minecraft:moon`.
  `StandardTimelines` holds those two timelines (read from 26.3's `timeline/day.json` and `moon.json`).
- `ClockPort`: the seam a bridge implements. `world`, `gameTime`, `state`, `apply`, `tick`, `sleep(dimension)`,
  `sync`. `fixtures/MemoryClockPort.java` is the in-memory reference. `ClockSync`, `NetworkState` and `ClientClocks` are what
  crosses to a client and what a client does with it.

## What the game does (and so the model)

Read with `javap -p -c` from the 26.3 and 1.21.1 jars on 2026-10-12. The model follows these, class for class.

**A tick** (`ServerClockManager.ServerClockInstance.tick`, called once per server tick by `ServerClockManager.tick`
while the `advance_time` rule holds):

```
if (!paused) { partialTick += rate; int n = Mth.floor(partialTick); partialTick -= n; totalTicks += n; }
```

All float. `Mth.floor(float)` is `(int) Math.floor((double) v)`. A clock with the rule off, or paused, does not change at
all, fraction included. The rule is read by the manager, so `tick(state, advanceTime)` takes it.

**Commands** (`ServerClockManager`): `setTotalTicks` sets the ticks and zeroes the fraction. `addTicks` is
`totalTicks = max(totalTicks + n, 0)` and leaves the fraction. `setPaused` and `setRate` set a field. Each change is
broadcast to clients.

**A marker** (`ClockTimeMarker`, made from a timeline's `TimeMarkerInfo`, taking the timeline's period):

```
occursAt(t):           no period: ticks == t        period p: ticks == t % p
getRepetitionCount(t): no period: t >= ticks ? 1 : 0   period p: t / p + (t % p >= ticks ? 1 : 0)
resolveTimeToMoveTo(t): no period: ticks             period p: t + durationToNext(p, t % p, ticks)
durationToNext(p, current, target): d = target - current; d > 0 ? d : p + d
```

**Moving to a marker** (`ServerClockManager.moveToTimeMarker`): no such marker: `NO_TIME_MARKER_FOUND` (this contract's
`NO_SUCH_MARKER`); `occursAt(totalTicks)`: `NOT_MOVED`; else `totalTicks = resolveTimeToMoveTo(totalTicks)`, fraction
zeroed, `MOVED`. Rate and pause are kept.

**Timelines** (`Timeline`): `getPeriodCount = (int) (total / period)` and 0 without a period; `getCurrentTicks = total %
period`, or the total without one. The model counts periods in a `long` where the game narrows to `int` (they differ
only after 2^31 periods: 51 trillion ticks on a day timeline).

**Sleep** (26.3 `ServerLevel.tick`): with enough players deeply asleep, `if (advance_time && dimension has a default
clock) clockManager.moveToTimeMarker(defaultClock, minecraft:wake_up_from_sleep)`. Then players wake. The `day` timeline's
`wake_up_from_sleep` marker is at tick 0.

**Sync** (`ServerClockInstance.packNetworkState`, `ClientClockManager`): the server sends `(totalTicks, partialTick,
rate)`, with rate 0 if the clock is paused or the rule is off. A client holds it and, each client tick, with `elapsed`
game ticks since its last: `sum = (double) partial + (double) elapsed * (double) rate; n = Mth.floor(sum);
partial = (float) (sum - n); total += n`. Receiving a sync first ticks every clock to the sync's game time, then replaces
the named clocks. Clients are told on join and on change, not periodically.

**The game before 26.1** (1.21.1 bytecode, the same in 1.20.1):

```
ServerLevel.tickTime:   gameTime + 1;  if (doDaylightCycle) setDayTime(getDayTime() + 1)
ServerLevel.tick, sleep: if (doDaylightCycle) { long l = getDayTime() + 24000L; setDayTime(l - l % 24000L); }
DimensionType.moonPhase: (int) (t / 24000L % 8L + 8L) % 8
TimeCommand.getDayTime:  (int) (dayTime % 24000L);  the day count is dayTime / 24000L
```

## Choices the model made

- **The carry is a float, exactly as the game's.** The alternative, an exact rational, would be cleaner and would not be
  the game: two mods that each add a fraction on a long-lived clock would drift apart from vanilla. The reference was
  compared with the real 26.3 classes (below), bit for bit.
- **The client is not the server.** The server adds floats tick by tick; a client multiplies in double and stores a
  float. They agree exactly where float arithmetic is exact (rates that are multiples of a power of two), and within one
  tick otherwise, over a day of unsynced time. They are different code in the game; C5 states the agreement the game has.
- **The model keeps periodic markers forward and one-shot markers literal.** A marker with no period moves a clock to its
  tick, which is *earlier* if the clock has passed it. That is the game. Decision 0018 first said markers never move
  backwards; that holds for every periodic marker (every marker of a timeline with a period), and C2 states both.
- **`NOT_MOVED` is a result, not a no-op.** A clock exactly at a periodic marker is not moved (not moved a whole period).
  The old game's sleep skip, at exactly a multiple of 24000, moves a whole day; C4 pins that one tick as the difference.
- **Marker ids are unique per clock.** The game keeps one map per clock and the later registration wins silently; a
  `ClockWorld` refuses a duplicate so a command means one thing.

## Laws

| Law | Statement | Labels |
|---|---|---|
| C1 | **Advance.** Over n ticks at rate r, not paused, total ticks grow by the floor of the accumulated n times r, the fraction carried; paused, or with the rule off, nothing changes, the fraction included. Any sequence of ticks, pauses, rates, sets and adds tracks an exact accounting of the same commands. | `C1:integer-rate-advances`, `C1:fraction-carries`, `C1:standstill`, `C1:accumulates-n-times-rate`, `C1:sequence-tracks-exact` |
| C2 | **Markers move forward.** Moving to a periodic marker lands on its next occurrence strictly after now (at most a period away, on the marker), never earlier; a clock exactly at it is `NOT_MOVED`; a move or a set lands on the tick with the fraction zeroed and rate and pause kept; a marker the clock lacks is `NO_SUCH_MARKER` and changes nothing. A marker with no period moves the clock to its tick. Set and add do what they say and nothing else. | `C2:moves-forward-only`, `C2:at-marker-not-moved`, `C2:lands-clean`, `C2:no-such-marker`, `C2:one-shot-goes-to-its-tick`, `C2:set-add-exact`, `C2:marker-inside-period`, `C2:marker-ids-unique-per-clock` |
| C3 | **Periods agree.** Ticks in the period and the period count recompose the total exactly; the repetition count, `occursAt` and the previous and next occurrence agree with counting occurrences one by one; the edges between two totals are the occurrences after the first and up to the second, each once, however sparse the samples, none going backwards. A timeline with no period counts none. | `C3:periods-recompose`, `C3:non-periodic-timeline`, `C3:repetition-count`, `C3:occurs-at`, `C3:previous-next-occurrence`, `C3:edges-each-once`, `C3:dawn-is-the-day-edge` |
| C4 | **The old game is a clock.** One clock at rate one, a 24000 day and a 192000 moon reproduce pre-26.1 day time, moon phase, day count and sleep skip, exactly, as the formulas above compute them, including through the port over a long script. Sleeping moves the dimension's default clock only; a clock without the wake marker, or a dimension without a clock, does not skip; the rule off does not skip. A mod's clock is a clock like any other. | `C4:tick`, `C4:sleep-skip`, `C4:rule-off-no-skip`, `C4:moon-phase`, `C4:day-count`, `C4:sleep-at-the-marker`, `C4:script-matches-old-game`, `C4:sleep-uses-default-clock`, `C4:sleep-moves-default-clock-only`, `C4:no-wake-marker-no-skip`, `C4:no-default-clock-no-skip`, `C4:default-clock-listed`, `C4:a-mods-clock` |
| C5 | **Sync agrees.** A client extrapolating from a synced `(totalTicks, partialTick, rate)` over the same game time reads the server's ticks: exactly where float arithmetic is exact, within one tick otherwise; a clock that is paused or whose rule is off is sent with rate zero and reads flat; a client told of every change and of everything on join follows every clock. | `C5:still-is-sent-still`, `C5:sync-agrees-exactly`, `C5:sync-within-one-tick`, `C5:join-and-change-agree` |
| C6 | **Determinism.** The same states and commands give the same results, with no wall clock and nothing remembered between calls. | `C6:deterministic` |

**C4 and the one tick.** `moveToTimeMarker` answers `NOT_MOVED` when the clock is exactly at the marker. The pre-26.1
sleep skip at `dayTime % 24000 == 0` computes `(t + 24000) - (t + 24000) % 24000 = t + 24000`: a whole day. So the two
differ at exactly that tick, which sleeping (possible only at night) can never reach, and the model follows 26.x.
`C4:sleep-at-the-marker` pins both answers so the difference is visible, not hidden. Everywhere else the skip is the
next multiple of 24000, checked for every tick of three days and for 3000 random times.


## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `DroppedCarry` | Whole ticks only; the fraction is thrown away each tick, so rate one half never advances | `C1:fraction-carries` |
| `RuleBlind` | Ignores the `advance_time` rule | `C1:standstill` |
| `PauseBlind` | A paused clock keeps running | `C1:standstill` |
| `BackwardsMarker` | A periodic marker resolves to this period's occurrence even when passed: the clock moves back | `C2:moves-forward-only` |
| `AlwaysMoves` | A clock exactly at the marker is moved a whole period on | `C2:at-marker-not-moved` |
| `StalePartial` | A move or a set keeps the old fraction | `C2:lands-clean` |
| `SilentAbsentMarker` | A marker the clock lacks is reported as "already there" | `C2:no-such-marker` |
| `AddDropsFraction` | Adding ticks drops the fraction as setting does | `C2:set-add-exact` |
| `OffByOneRepetition` | An occurrence counts only after its tick, not at it | `C3:repetition-count` |
| `PeriodsFromOne` | Periods numbered from one | `C3:periods-recompose` |
| `EarlySleepSkip` | Sleeping lands one tick before the wake marker | `C4:sleep-skip` |
| `SleepIgnoresRule` | Sleeping skips the night with time not advancing | `C4:rule-off-no-skip` |
| `LatePhase` | The moon's phase is one eighth late | `C4:moon-phase` |
| `SleepMovesEveryClock` (a port) | Sleeping moves every clock that has a wake marker, not the dimension's | `C4:sleep-moves-default-clock-only` |
| `SleepsWithoutAClock` (a port) | A dimension with no clock skips by moving another | `C4:no-default-clock-no-skip` |
| `RateIgnoringExtrapolation` | A client adds elapsed ticks and never looks at the rate | `C5:sync-agrees-exactly` |
| `ForgetfulClient` | A client forgets the fraction it carries between ticks | `C5:sync-agrees-exactly` |
| `RunningWhenStillSync` | Sends the stored rate even when paused or the rule is off | `C5:still-is-sent-still` |
| `RemembersCalls` | Gives away a free tick every thousandth call: same input, different answer | `C6:deterministic` |
| `NoOpClockModel` | Time never passes; no command changes a clock; no marker occurs | `C1:integer-rate-advances` |

`NoOpClockModel` breaks no rule about moving the wrong way, since it never moves. It is in the matrix because this is a
model contract: C1 says a running clock advances, and a clock that does nothing must fail it.

## Using it

A consumer declares a `ClockWorld` (its clocks, timelines and each dimension's default clock) and reads time through a
`ClockModel`; it never recomputes a day count, a phase or a marker's next occurrence by hand. `edgesBetween` replaces a
hand-written "did the day change" and counts every boundary crossed.

A **bridge** implements `ClockPort` and runs the same laws against it (`WorldClockContractTests` takes a port factory).
The native bridge (26.1 and later) maps `state`, `apply`, `tick` and `sync` onto `ServerClockManager`, `Timeline` and the
time packet, and generates the world's clocks and timelines as datapack JSON (`world_clock/`, `timeline/`). The emulated
bridge (before 26.1) keeps a mod's own clocks in one saved data on the overworld, advances them on the server tick, syncs
them with a small payload on join and change, and evaluates timelines with the model; the overworld clock is vanilla
`dayTime` (rate one; paused is `doDaylightCycle` off). Both are built on decision 0016's machinery and are not part of
this package.

A client of an emulated bridge uses `ClientClocks`, which is the game's client formula, so a mod's clock on 1.21.1 reads
as it would on 26.3.

## Evidence

A passing harness is design-time evidence about the model and the matrix, never a claim about the game (DESIGN §11). The
harness checks the model against the pre-26.1 formulas it quotes (C4). Separately, on 2026-10-12 the reference was run
once against the real 26.3 classes loaded from the game jar (a throwaway differential run, not part of this repository's
gates): 7,376,997 `ServerClockInstance.tick` steps, 900,000 `ClientClockManager.tick` steps (with deltas of one tick and
of up to forty) and 20,000 `ClockTimeMarker` cases (`occursAt`, `resolveTimeToMoveTo`, `getRepetitionCount`, with and
without a period) agreed with the model bit for bit. The bridges' own conformance gametests, on 26.3 and on 1.21.1, are
what turns this into a claim about the game: `bridges/world-clock` is built, linked on every pinned version, and its cases pass
on 26.3 (native) and 1.21.1 (emulated) in Somnium (decision 0018, "Evidence").

## Not covered

- The takeover, the offered variant that bends vanilla's sky, moon, mobs and beds to a clock that is not the overworld default
  on versions before 26.1, is not built (decision 0018). On those versions a mod's own clocks are real to every mod that reads
  them, and vanilla keeps following `dayTime`. The native and emulated bridges are built (`bridges/world-clock`).
- Environment-attribute tracks (26.x's keyframed sky colours, light, villager schedule, mob burning). The model has
  periods and markers, not tracks. `villager_schedule` and `early_game` are not declared.
- Player-facing commands, their parsing and permissions: `ClockCommand` is the operation, not the syntax.
- The `/time` commands of versions before 26.1 that bypass the model's domain: `/time add` there can make `dayTime`
  negative (26.x clamps at zero, the model's `ClockState` refuses it), and `/time set` accepts any value.
- Per-tick cost, persistence format and the saved data's schema (`minecraft:world_clocks`).
- The game's `int` narrowing of the period count beyond 2^31 periods, and rates above 1e9.
- Threads: a port is single-threaded, called from the server thread.

## Versioning and packaging

This is `world.clock@1`. A new law, label, fixture or optional method that keeps every existing one stays in `@1`; a change
to a law's meaning or a label is `@2`. The namespace `mycelium.world_clock.v1` is development-time; a consuming mod
renamespaces `model` under its own artifact-owned prefix, and `fixtures` and `harness` are never shipped. The model is
Java 8 linkable.

## Running the harness

From the Mycelium root: `bash run-contracts.sh world-clock` (or `.\run-contracts.ps1 world-clock`), or `./gradlew
myceliumVerify -Pmycelium.contracts=world.clock@1`. Expected: the reference clean, every matrix entry `CAUGHT`, exit code 0.
