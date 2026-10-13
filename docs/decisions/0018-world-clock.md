# 0018 — world.clock@1: the 26.1 clock model, on every version


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


### Laws

| Law | Statement |
|---|---|
| C1 | **Advance.** Over n ticks at rate r, not paused, total ticks grow by floor of the accumulated n·r, the fraction carried; paused or rule off, nothing moves. |
| C2 | **Markers move forward.** `moveTo` lands on the marker's next occurrence at or after now, never earlier; a marker the clock does not have is `NO_SUCH_MARKER` and changes nothing. |
| C3 | **Periods agree.** For a periodic timeline, ticks-in-period and period count recompose total ticks exactly; edges between two states are exactly the marker occurrences between them, each once. |
| C4 | **The old game is a clock.** The model with one clock at rate one, a 24000 day and a 192000 moon reproduces pre-26.1 day time, moon phase (`totalTicks / 24000 % 8`), day count and sleep skip (next multiple of 24000) exactly. |
| C5 | **Sync agrees.** A client extrapolating from a synced (totalTicks, partialTick, rate) at the same game time reads the server's ticks. |
| C6 | **Determinism.** The same states and commands give the same results, with no wall clock. |

Rejection matrix: a clock that drops the fractional carry (C1), one that moves backwards to a marker (C2), off-by-one period
counts (C3), a model whose sleep skip lands one tick early (C4), extrapolation that ignores rate (C5), a `NoOp` (all).

### Bridges (decision 0016)

- `native-clock` (from 26.1): the port maps onto `ServerClockManager`, `Timeline` and the time packet. A mod's clocks and
  timelines can be generated as datapack JSON (`world_clock/`, `timeline/`) from its declaration.
- `emulated-clock` (before 26.1): the overworld clock is vanilla `dayTime` (rate one; paused is `doDaylightCycle` off); a
  mod's own clocks live in one saved data on the overworld, advance on the server tick, and sync with a small payload on
  join and change. Timelines, markers and periods are evaluated by the pure model. No mixin is needed for clocks a mod adds.
- `emulated-clock-takeover` (offered, never default, before 26.1): mixins that let vanilla follow a non-default overworld
  clock (rate, pause, a wake marker other than the next 24000): the sleep skip in `ServerLevel.tick`, `Level.getDayTime`
  and `LevelTimeAccess.dayTime` for sky, moon and mobs, the client's day-time path. Each mixin target is named in the bridge
  and linkage-checked on its versions; a mod takes it only if it wants vanilla bent.

### Honest limits

On versions before 26.1, a mod's own clocks are fully real to every mod that reads them; vanilla's sky, moon, villagers,
mobs and beds keep following `dayTime` unless the takeover variant is taken. Environment-attribute tracks (26.x's keyframed
sky colours, light, mob burning) are not emulated by this contract.

## First proofs

