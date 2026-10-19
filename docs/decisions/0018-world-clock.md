# 0018 — world.clock@1: the 26.1 clock model, on every version

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted. Pure half built (2026-10-12); bridges `native-clock` and `emulated-clock` built and proven in Somnium (2026-10-13); `emulated-clock-takeover` declared here and not built. Written for Cartomancy and Somnium. |
| Ground truth | the 26.3, 1.21.1 and 1.20.1 game jars read with `javap` (2026-10-12), and for the bridges the jars of 26.1 to 26.3 and of 1.14.4 to 1.21.11 (2026-10-13); https://minecraft.wiki/w/World_clock; https://minecraft.wiki/w/Commands/time |

## Context

26.1 replaced the single day time with **world clocks**. Read from 26.3's classes:

- A clock (`net.minecraft.world.clock.WorldClock`) is an empty record in a datapack registry: identity only.
- Its state is server-global (`ServerClockManager`, saved data `minecraft:world_clocks`): `ClockState(totalTicks, partialTick,
  rate, paused)`. Time advances once per server tick by `rate` (a float; fractions carry), not while paused, and only while
  the `advance_time` rule holds.
- Meaning lives in **timelines** (`net.minecraft.world.timeline.Timeline`): a clock, an optional `period_ticks`, named **time
  markers** (`day` 1000, `noon` 6000, `night` 13000, `midnight` 18000, `wake_up_from_sleep`, `roll_village_siege`), and
  keyframe tracks of environment attributes. Day count is a timeline's period count; moon phase is the `moon` timeline
  (period 192000).
- A dimension type names its default clock (`default_clock`); sleeping moves that clock to `wake_up_from_sleep`
  (`moveToTimeMarker`), and a clock without that marker does not skip.
- Clients learn clocks from `ClientboundSetTimePacket(gameTime, Map<clock, (totalTicks, partialTick, rate)>)` on change and
  join, and extrapolate by rate.

Before 26.1 (1.21.1 and 1.20.1 alike) there is one implicit clock: `dayTime`, shared by every dimension, advancing by one
while `doDaylightCycle`, with a hard-coded 24000-tick day, eight moon phases, sleep skipping to the next multiple of 24000,
and periodic sync of `(gameTime, dayTime)`.

## Decision

`world.clock@1` models time the way 26.1 does, because that model is the general one and the older game is a special case of
it: one clock, rate one, a 24000-tick `day` timeline and a 192000-tick `moon` timeline.

### The model (pure)

`ClockModel` evaluates immutable clock declarations and state; `ClockPort` supplies the platform operations. Timelines,
markers, rates, and synchronization are defined by `contracts/world-clock/SPEC.md`. Native and emulated bridges implement
the same port, with realizations selected from `eras/minecraft.json`.

### Laws

| Law | Statement |
|---|---|
| C1 | **Advance.** Over n ticks at rate r, not paused, total ticks grow by floor of the accumulated n·r, the fraction carried; paused or rule off, nothing moves. |
| C2 | **Markers move forward.** `moveTo` a periodic marker lands on its next occurrence strictly after now, never earlier, and a clock exactly at it is `NOT_MOVED`; a marker with no period moves the clock to its own tick (earlier, if passed: the game's behaviour); a marker the clock does not have is `NO_SUCH_MARKER` and changes nothing. |
| C3 | **Periods agree.** For a periodic timeline, ticks-in-period and period count recompose total ticks exactly; edges between two states are exactly the marker occurrences between them, each once. |
| C4 | **The old game is a clock.** The model with one clock at rate one, a 24000 day and a 192000 moon reproduces pre-26.1 day time, moon phase (`totalTicks / 24000 % 8`), day count and sleep skip (next multiple of 24000) exactly. |
| C5 | **Sync agrees.** A client extrapolating from a synced (totalTicks, partialTick, rate) at the same game time reads the server's ticks: exactly where float arithmetic is exact, within one tick otherwise (the server adds floats tick by tick, the client multiplies in double, as in the game). |
| C6 | **Determinism.** The same states and commands give the same results, with no wall clock. |

Rejection matrix: a clock that drops the fractional carry (C1), one that moves backwards to a marker (C2), off-by-one period
counts (C3), a model whose sleep skip lands one tick early (C4), extrapolation that ignores rate (C5), a `NoOp` (all).

### Bridges (decision 0016)

- `native-clock` (from 26.1): the port maps onto `ServerClockManager`, `Timeline` and the time packet. A mod's clocks and
  timelines can be generated as datapack JSON (`world_clock/`, `timeline/`) from its declaration. (Built; 26.1 and 26.2 need an
  overlay, below.)
- `emulated-clock` (before 26.1): the overworld clock is vanilla `dayTime` (rate one; paused is `doDaylightCycle` off); a
  mod's own clocks live in one saved data on the overworld, advance on the server tick, and sync with a small payload on
  join and change. Timelines, markers and periods are evaluated by the pure model. No mixin is needed for clocks a mod adds.
- `emulated-clock-takeover` (offered, never default, before 26.1): mixins that let vanilla follow a non-default overworld
  clock (rate, pause, a wake marker other than the next 24000): the sleep skip in `ServerLevel.tick`, `Level.getDayTime`
  and `LevelTimeAccess.dayTime` for sky, moon and mobs, the client's day-time path. Each mixin target is named in the bridge
  and linkage-checked on its versions; a mod takes it only if it wants vanilla bent. (Not built: see "Not built", below.)

### Honest limits

On versions before 26.1, a mod's own clocks are fully real to every mod that reads them; vanilla's sky, moon, villagers,
mobs and beds keep following `dayTime` unless the takeover variant is taken. Environment-attribute tracks (26.x's keyframed
sky colours, light, mob burning) are not emulated by this contract.

## First proofs

Further consumer game evidence is required for the targets and behaviours not exercised here.

## Evidence (pure half, 2026-10-12)

`contracts/world-clock` is built: model, reference, `ClockPort` with an in-memory reference, a rejection matrix with a `NoOp`
entry, laws C1 to C6 with seeded property tests (`bash run-contracts.sh world-clock` clean, every entry caught;
`./gradlew check` green). Its SPEC quotes the 26.3 and 1.21.1 methods it follows. The reference was also run once against the
real 26.3 classes from the game jar (7,376,997 server tick steps, 900,000 client tick steps, 20,000 marker cases) and agreed
bit for bit; that run is not repeatable from this repository and is design-time evidence, not game evidence.


## Bridges as built (2026-10-13)

`bridges/world-clock` (`bridge.json`, realization table `world.clock` in `eras/minecraft.json`; `BRIDGE-PACKAGE.md`):

| Range | Variant | Overlays (replace same-named files of the variant) |
|---|---|---|
| 26.1 to 26.x | `native-clock` | `26.1` (26.1 to 26.2): `Game` |
| 1.14 to 1.21.11 | `emulated-clock` | `1.14` (1.14 to 1.15.2), `1.16` (1.16 to 1.16.5), `1.17` (1.17 to 1.20.1), `1.20.2`, `1.20.3` (1.20.3 to 1.20.4), `1.21.5` (1.21.5 to 1.21.10), `1.21.11`: `Game` and `ClockStore` |
| c0.0.11a to 1.13.2 | none | the bridge is written in Mojang's names, which exist from 1.14.4 |

Both variants have one class that implements the port, `WorldClocks`, with the same surface, so a mod's code is the same on every
version: `WorldClocks.of(server, world[, transport])`, the port (`world`, `gameTime`, `state`, `apply`, `tick`, `sleep`, `sync`),
`onServerTick()`, `onPlayerJoin(player)`, `problems()`, and `WorldClocks.Client` (`read(level, clock)`, `overworld(level)`, `receive(bytes)`,
`reset()`). Each variant isolates what changes with the version in `Game` (and, emulated, `ClockStore`): the other files are one
source on every version of the variant. What a mod supplies:

- **26.1 and later: nothing.** The game advances its clocks (once per server tick, while the `advance_time` rule holds and the tick rate
  manager runs normally), syncs them to clients on change and on join, and sleeps by them; `onServerTick`, `onPlayerJoin` and `Client.receive`
  do nothing. A mod's own clocks and timelines are datapack files, which `ClockDatapack.files(world, namespace)` writes from the declaration
  and `problems()` holds the game's data to. The game loads clocks when the world loads: a `/reload` does not add one.
- **Before 26.1: two calls and, for a clock it reads on a client, a transport.** `onServerTick()` every server tick and `onPlayerJoin(player)` when
  a player joins (the loaders name those events differently and the game has no manager to do it), and a `Transport` (`toAll`, `toPlayer`:
  how this loader sends a custom payload) with `Client.receive(bytes)` on the client thread. The bridge writes and reads the bytes
  (`ClockWire`, plain `java.io`, the same on every version and loader); the mod only delivers them. A mod that adds no clock of its own, or reads
  none on a client, passes `Transport.NONE` and the bridge sends nothing and saves nothing.

How the pre-26.1 port is built, and what it refuses: the overworld clock **is** vanilla's `dayTime` (a read is `getDayTime`, a set or an add is
`setDayTime`, paused is the daylight rule off, so `Pause` and `Resume` set the rule); a rate for it other than one is an
`UnsupportedOperationException`, not a pretence, since the game would not follow. Every other clock a mod declares (`minecraft:the_end` included,
which the old game never had) is one line of text in one saved data on the overworld (`mycelium_world_clocks`), kept to the bit (the floats as
hexadecimal), saved only when a clock changed, and a clock the save has and the declaration lacks is kept. The tick hook advances those clocks by the
model unless the rule is off or the tick rate manager is frozen or stepping (1.20.3 on), and tells clients when the rule changes what they
extrapolate. `problems()` names what a declaration asks that this variant cannot give (a default clock for the overworld other than the
overworld's, a wake marker other than tick 0 of a 24000-tick day: the takeover's).

### What reading the game corrected or added

- 26.1 and 26.2 (read from the 26.1, 26.1.1, 26.1.2 and 26.2 server jars) are not 26.3's manager. `ServerClockManager.moveToTimeMarker` returns a
  `boolean` (moved or not: "no such marker" and "already there" are not told apart); a clock's state is public only as the packed copy
  `packState()`; there is no `getInstance`/`ClockInstance`, and a client's `ClockManager` answers `getTotalTicks` and nothing else (no fraction, no
  rate). The overlay reads state from `packState`, sync from `createFullSyncPacket`, and tells the three outcomes of a move from the markers the
  game's timelines register. (The 26.1 primer's `skipToTimeMarker` is not in any of these jars.) 26.3 gives each clock an instance
  (`totalTicks`, `partialTick`, `rate`, `isPaused`) and the move three outcomes.
- 26.x runs `ServerClockManager.tick` once per server tick only while the tick rate manager runs normally, and the manager reads the *global*
  rule `advance_time` itself. So the native port's `tick(advanceTime)` takes the rule as an assertion (it must be what the rule is) rather than a
  switch it could throw.
- Every registered timeline's markers are registered on its clock at server start, whether or not a dimension type lists the timeline: a mod's
  timeline needs no dimension to be real.
- `ClockTimeMarkers.createKey(String)` names only `minecraft:` markers; a mod's marker key is `ResourceKey.create(ClockTimeMarkers.ROOT_ID, id)`.
- The rule is `advance_time` from **1.21.11** (`GameRules.ADVANCE_TIME`, read from a level), with the old one `dayTime` still the whole clock; up to 1.21.10 it
  is `doDaylightCycle` (`RULE_DAYLIGHT`).
- Saved data has five shapes before 26.1: a name in the constructor and `load(CompoundTag)` (1.14 to 1.16), a loader function (1.17 to 1.20.1), a
  `Factory` with `save(CompoundTag)` (1.20.2 to 1.20.4), a `Factory` with a registry provider (1.20.5 to 1.21.4), a `SavedDataType` with a codec
  (1.21.5 on); the tick rate manager arrives in 1.20.3; 1.14 and 1.15 find the overworld through the dimension type.

### Honest limits

- The pre-26.1 overworld clock has rate one: `SetRate` on it is refused. A client of the emulated bridge cannot read the overworld's rate or its
  fraction (the old game sends `dayTime` once a second and the client counts), only a mod's own clocks, which it is told.
- 26.1 and 26.2 give a client only the clock's total ticks; its fraction and rate are the server's to hold.
- `Client.read` on 26.x for a clock that is not yet synced is empty, not zero.
- The old game lets `/time add` take `dayTime` below zero; the model's clock stops at zero, so a negative reading is read as zero.
- A mod that ships the bridge's conformance cases in its `main` source set also ships the conformance clock (`mycelium_conformance:calendar`, a
  datapack clock and timeline) on 26.1 and later; a `gametest` source set carries it out of the jar.

### Evidence (2026-10-13)

- **Linkage** (`myceliumBridgeLinkage`, inside `./gradlew check`): `native-clock` is compiled against 26.3 and its references linked on
  26.1, 26.1.1, 26.1.2, 26.2 and 26.3; with the `26.1` overlay it is compiled and linked for 26.1 to 26.2. `emulated-clock` and each overlay
  are compiled against the newest pinned version of their group and linked on every pinned version of it: 1.14.4 and 1.15.2 (overlay 1.14),
  1.16.5 (1.16), 1.17.1, 1.18.2, 1.19.2, 1.19.4, 1.20.1 (1.17), 1.20.4 (1.20.3), 1.20.5, 1.20.6, 1.21, 1.21.1, 1.21.3, 1.21.4 (base),
  1.21.5, 1.21.8, 1.21.10 (1.21.5), 1.21.11 (1.21.11). 1.20.2 has no name data: its overlay was compiled by hand against the 1.20.2 jar, as
  were the 1.14 and 1.16 overlays before their data was pinned.
- **Conformance in Somnium** (`world_clock_conformance`, `_follows_the_loop`, `_declaration`, `_dawn_edge`): pass on `fabric-26.3` (native, the
  game's own manager, packet and period code) and on `fabric-1.21.1` (emulated: `dayTime`, the daylight rule, saved data and the bridge's
  payload). The first run found one fault in the rig, a `RuntimeException` where it expected `ClassNotFoundException` from Fabric's loader when
  asked for a client class on a server; the catch now covers it. Those runs establish the listed targets only; no broader matrix is reproducible from this repository.
- **Not run in a game**: 26.1 and 26.2 (linked, and their overlay's game calls read from the jars), every pre-26.1 version except 1.21.1, and
  `ClockConformance.sleeping`, the case that puts a real sleeper in a bed. In Somnium a mock player in a bed did not make the server skip the
  night on 26.3 or on 1.21.1 (the clock was at 85140 after 140 ticks, from 85000): a gametest's mock player is not what the server counts
  as sleeping, so the rig is written and its failure is the rig's, not the game's. `sleep_moves_the_default_clock`, which sleeps the clock
  through the port, is proven. A consumer with a real harness can use `sleeping`; none has yet.

### Not built

`emulated-clock-takeover` (before 26.1, optional, offered and never default) is declared above and **not built**. It needs three things
that were not here: machinery for a variant a contract row does not bring (0016's "offer, never police" has no `offered` marking yet in
`bridge.json`), mixin targets that the linkage gate can see (Mixin annotations are not stubbed; a target written as a string is
invisible to it), and a proof in a real game harness for the hooks it would add. The design stands: the 1.21.1 sleep skip is
`ServerLevel.tick`'s `setDayTime(l - l % 24000L)`, which a `@ModifyArg` could move to the next occurrence of the declared wake marker. The adopting consumer did not require takeover, so it was left for work with a consumer and a game harness.
