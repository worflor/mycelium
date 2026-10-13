package mycelium.world_clock.v1.fixtures;

import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.GameMath;
import mycelium.world_clock.v1.model.Marker;
import mycelium.world_clock.v1.model.Move;
import mycelium.world_clock.v1.model.MoveResult;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.ReferenceClockModel;
import mycelium.world_clock.v1.model.Timeline;
import mycelium.world_clock.v1.model.Applied;

import java.util.Optional;

/** Broken clocks, each the reference with one rule wrong: the mistake a mod really makes (SPEC.md, rejection matrix). */
public final class ClockVariants {
    private ClockVariants() {
    }

    /** Whole ticks only: the fraction is thrown away each tick, so a rate of one half never advances (C1). */
    public static ClockModel droppedCarry() {
        return new ReferenceClockModel() {
            @Override
            public ClockState tick(ClockState state, boolean advanceTime) {
                if (!advanceTime || state.paused()) {
                    return state;
                }
                int whole = GameMath.floor(state.partialTick() + state.rate());
                return new ClockState(state.totalTicks() + whole, 0.0F, state.rate(), state.paused());
            }
        };
    }

    /** Ignores the advance_time rule: a world told to stand still keeps running (C1). */
    public static ClockModel ruleBlind() {
        return new ReferenceClockModel() {
            @Override
            public ClockState tick(ClockState state, boolean advanceTime) {
                return super.tick(state, true);
            }
        };
    }

    /** Ignores a clock's pause: a paused clock keeps running (C1). */
    public static ClockModel pauseBlind() {
        return new ReferenceClockModel() {
            @Override
            public ClockState tick(ClockState state, boolean advanceTime) {
                return super.tick(state.withPaused(false), advanceTime).withPaused(state.paused());
            }
        };
    }

    /** A periodic marker resolves to this period's occurrence even when the clock has passed it: moves backwards (C2). */
    public static ClockModel backwardsMarker() {
        return new ReferenceClockModel() {
            @Override
            public long resolveTimeToMoveTo(Marker marker, long totalTicks) {
                if (!marker.periodic()) {
                    return marker.ticks();
                }
                int period = marker.periodTicks().getAsInt();
                return totalTicks - totalTicks % period + marker.ticks();
            }
        };
    }

    /** A clock exactly at the marker is moved a whole period on instead of left where it is (C2). */
    public static ClockModel alwaysMoves() {
        return new ReferenceClockModel() {
            @Override
            public Move moveTo(ClockState state, Optional<Marker> marker) {
                if (!marker.isPresent()) {
                    return new Move(MoveResult.NO_SUCH_MARKER, state);
                }
                return new Move(MoveResult.MOVED, new ClockState(resolveTimeToMoveTo(marker.get(), state.totalTicks()),
                        0.0F, state.rate(), state.paused()));
            }
        };
    }

    /** A move or a set keeps the old fraction, so the clock lands a little past the tick it was sent to (C2). */
    public static ClockModel stalePartial() {
        return new ReferenceClockModel() {
            @Override
            public Move moveTo(ClockState state, Optional<Marker> marker) {
                Move move = super.moveTo(state, marker);
                if (move.result() == MoveResult.MOVED) {
                    return new Move(MoveResult.MOVED, move.state().withPartialTick(state.partialTick()));
                }
                return move;
            }

            @Override
            public ClockState set(ClockState state, long totalTicks) {
                return state.withTotalTicks(totalTicks);
            }
        };
    }

    /** A marker the clock does not have is reported as "already there" instead of "no such marker" (C2). */
    public static ClockModel silentAbsentMarker() {
        return new ReferenceClockModel() {
            @Override
            public Move moveTo(ClockState state, Optional<Marker> marker) {
                if (!marker.isPresent()) {
                    return new Move(MoveResult.NOT_MOVED, state);
                }
                return super.moveTo(state, marker);
            }
        };
    }

    /** Adding ticks drops the fraction as setting does (C2). */
    public static ClockModel addDropsFraction() {
        return new ReferenceClockModel() {
            @Override
            public ClockState add(ClockState state, int ticks) {
                return super.add(state, ticks).withPartialTick(0.0F);
            }
        };
    }

    /** Counts an occurrence only after its tick, not at it: off by one at the marker (C3). */
    public static ClockModel offByOneRepetition() {
        return new ReferenceClockModel() {
            @Override
            public long repetitionCount(Marker marker, long totalTicks) {
                if (!marker.periodic()) {
                    return totalTicks > marker.ticks() ? 1L : 0L;
                }
                int period = marker.periodTicks().getAsInt();
                return totalTicks / period + (totalTicks % period > marker.ticks() ? 1L : 0L);
            }
        };
    }

    /** Numbers periods from one: day one is the first day (C3). */
    public static ClockModel periodsFromOne() {
        return new ReferenceClockModel() {
            @Override
            public long periodCount(Timeline timeline, long totalTicks) {
                return timeline.periodTicks().isPresent() ? super.periodCount(timeline, totalTicks) + 1L : 0L;
            }
        };
    }

    /** At most one edge between two samples, however many occurrences lie between: sample observer's rule, not this one's (C3). */
    public static ClockModel oneEdgePerGap() {
        return new ReferenceClockModel() {
            @Override
            public long edgesBetween(Marker marker, long fromExclusive, long toInclusive) {
                return Math.min(1L, super.edgesBetween(marker, fromExclusive, toInclusive));
            }
        };
    }

    /** Sleeping lands one tick before the wake marker (C4). */
    public static ClockModel earlySleepSkip() {
        return new ReferenceClockModel() {
            @Override
            public Move sleep(ClockState state, Optional<Marker> wakeMarker, boolean advanceTime) {
                Move move = super.sleep(state, wakeMarker, advanceTime);
                if (move.result() == MoveResult.MOVED) {
                    return new Move(MoveResult.MOVED, move.state().withTotalTicks(move.state().totalTicks() - 1L));
                }
                return move;
            }
        };
    }

    /** Sleeping skips the night even when time is told not to advance (C4). */
    public static ClockModel sleepIgnoresRule() {
        return new ReferenceClockModel() {
            @Override
            public Move sleep(ClockState state, Optional<Marker> wakeMarker, boolean advanceTime) {
                return super.sleep(state, wakeMarker, true);
            }
        };
    }

    /** The moon's phase is one eighth late (C4). */
    public static ClockModel latePhase() {
        return new ReferenceClockModel() {
            @Override
            public int phase(Timeline timeline, long totalTicks, int phases) {
                return (super.phase(timeline, totalTicks, phases) + phases - 1) % phases;
            }
        };
    }

    /** A client that adds elapsed game time as whole ticks and never looks at the rate (C5). */
    public static ClockModel rateIgnoringExtrapolation() {
        return new ReferenceClockModel() {
            @Override
            public NetworkState extrapolate(NetworkState synced, long elapsedGameTicks) {
                return new NetworkState(synced.totalTicks() + elapsedGameTicks, synced.partialTick(), synced.rate());
            }
        };
    }

    /** A client that forgets the fraction it carries between ticks (C5). */
    public static ClockModel forgetfulClient() {
        return new ReferenceClockModel() {
            @Override
            public NetworkState extrapolate(NetworkState synced, long elapsedGameTicks) {
                double sum = (double) elapsedGameTicks * (double) synced.rate();
                return new NetworkState(synced.totalTicks() + GameMath.floor(sum), 0.0F, synced.rate());
            }
        };
    }

    /** Sends the stored rate even when the clock is paused or time is not advancing: clients keep running it (C5). */
    public static ClockModel runningWhenStillSync() {
        return new ReferenceClockModel() {
            @Override
            public NetworkState network(ClockState state, boolean advanceTime) {
                return new NetworkState(state.totalTicks(), state.partialTick(), state.rate());
            }
        };
    }

    /** Remembers how often it has been asked, and gives the thousandth tick away free: the same input, a different answer (C6). */
    public static ClockModel remembersCalls() {
        return new ReferenceClockModel() {
            private long calls;

            @Override
            public ClockState tick(ClockState state, boolean advanceTime) {
                ClockState next = super.tick(state, advanceTime);
                if (++calls % 1000 == 0 && advanceTime && !state.paused()) {
                    return next.withTotalTicks(next.totalTicks() + 1L);
                }
                return next;
            }
        };
    }

    /** A port that lets sleeping move every clock to its wake marker, not only the dimension's default (C4). */
    public static ClockPort sleepMovesEveryClock(ClockModel model, ClockWorld world) {
        return new MemoryClockPort(model, world) {
            @Override
            public Optional<Applied> sleep(String dimension, boolean advanceTime) {
                Optional<ClockId> clock = world.defaultClock(dimension);
                if (!clock.isPresent()) {
                    return Optional.empty();
                }
                Applied mine = null;
                for (ClockId each : world.clocks()) {
                    Applied a = sleepClock(each, advanceTime);
                    if (each.equals(clock.get())) {
                        mine = a;
                    }
                }
                return Optional.of(mine);
            }
        };
    }

    /** A port that skips the night in a dimension with no default clock by moving the first clock (C4). */
    public static ClockPort sleepsWithoutAClock(ClockModel model, ClockWorld world) {
        return new MemoryClockPort(model, world) {
            @Override
            public Optional<Applied> sleep(String dimension, boolean advanceTime) {
                Optional<ClockId> clock = world.defaultClock(dimension);
                ClockId target = clock.isPresent() ? clock.get() : world.clocks().iterator().next();
                return Optional.of(sleepClock(target, advanceTime));
            }
        };
    }
}
