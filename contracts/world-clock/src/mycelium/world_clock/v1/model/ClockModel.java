package mycelium.world_clock.v1.model;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a clock does, written as the game does it: the arithmetic the laws C1 to C6 govern (SPEC.md). Every method is a
 * pure function of its arguments; nothing here reads a wall clock or keeps state, so the same inputs give the same
 * outputs (C6). {@link ReferenceClockModel} is the reference; the fixtures break it one rule at a time.
 *
 * <p>Ticks are server ticks. A "total" is a clock's total ticks; a "period" is a timeline's {@code period_ticks}.
 */
public interface ClockModel {

    // ---- time passes (C1) ----

    /**
     * One server tick. A running clock (not paused) with {@code advanceTime} on adds its rate to the fraction, takes the
     * whole ticks off it into the total, and keeps the rest. Otherwise it is returned unchanged.
     */
    ClockState tick(ClockState state, boolean advanceTime);

    // ---- commands (C2) ----

    /** Sets the total and drops the fraction. */
    ClockState set(ClockState state, long totalTicks);

    /** Adds to the total, stopping at zero; the fraction stays. */
    ClockState add(ClockState state, int ticks);

    ClockState pause(ClockState state, boolean paused);

    ClockState rate(ClockState state, float rate);

    /**
     * Moves the clock to a marker's next occurrence. No marker: {@link MoveResult#NO_SUCH_MARKER}, state unchanged. The
     * clock exactly at the marker: {@link MoveResult#NOT_MOVED}. Otherwise {@link MoveResult#MOVED} to
     * {@link #resolveTimeToMoveTo}, with the fraction dropped; rate and pause are kept.
     */
    Move moveTo(ClockState state, Optional<Marker> marker);

    /**
     * Sleeping through the night, as the game does it: with {@code advanceTime} on, move the dimension's default clock to
     * its wake marker ({@link #moveTo}, so a clock without the marker does not skip); with it off, nothing moves.
     */
    Move sleep(ClockState state, Optional<Marker> wakeMarker, boolean advanceTime);

    // ---- markers (C2, C3) ----

    /** True when the marker occurs at this total: its tick, in a period-bound marker every period. */
    boolean occursAt(Marker marker, long totalTicks);

    /**
     * The total a clock at {@code totalTicks} is moved to. A periodic marker: the next occurrence strictly after now, so
     * a clock already past it this period goes to the next period's, and one exactly on it goes a whole period on (the
     * caller has already answered that case with {@code NOT_MOVED}). A marker with no period: its own tick, which is
     * earlier if the clock has passed it.
     */
    long resolveTimeToMoveTo(Marker marker, long totalTicks);

    /** How many times the marker has occurred at or before this total (including an occurrence exactly at it). */
    long repetitionCount(Marker marker, long totalTicks);

    // ---- timelines (C3, C4) ----

    /** Whole periods elapsed (a day count, a Great Year count); 0 for a timeline with no period. */
    long periodCount(Timeline timeline, long totalTicks);

    /** Ticks into the current period; the total itself for a timeline with no period. */
    long currentTicks(Timeline timeline, long totalTicks);

    /** Which of {@code phases} equal parts of the period the timeline is in (the moon: eight of 192000); 0 with no period. */
    int phase(Timeline timeline, long totalTicks, int phases);

    // ---- sync (C5) ----

    /** What a client is sent: the state, with rate zero when the clock is paused or {@code advanceTime} is off. */
    NetworkState network(ClockState state, boolean advanceTime);

    /** What a client does with a synced state after {@code elapsedGameTicks} of game time: advance by its rate. */
    NetworkState extrapolate(NetworkState synced, long elapsedGameTicks);

    // ---- derived: nothing a variant needs to restate ----

    /** Applies a command; a move reports its result. Markers are found through {@code markers}. */
    default Applied apply(ClockState state, ClockCommand command, MarkerLookup markers) {
        if (command instanceof ClockCommand.SetTicks c) {
            return new Applied(set(state, c.totalTicks()), Optional.empty());
        }
        if (command instanceof ClockCommand.AddTicks c) {
            return new Applied(add(state, c.ticks()), Optional.empty());
        }
        if (command instanceof ClockCommand.Pause) {
            return new Applied(pause(state, true), Optional.empty());
        }
        if (command instanceof ClockCommand.Resume) {
            return new Applied(pause(state, false), Optional.empty());
        }
        if (command instanceof ClockCommand.SetRate c) {
            return new Applied(rate(state, c.rate()), Optional.empty());
        }
        ClockCommand.MoveToMarker c = (ClockCommand.MoveToMarker) command;
        Move move = moveTo(state, markers.find(c.markerId()));
        return new Applied(move.state(), Optional.of(move.result()));
    }

    /**
     * The occurrences of a marker after {@code fromExclusive} and up to {@code toInclusive}: each one counted once,
     * however far apart the two totals are; none going backwards. Generalises {@code data.catalog@1}'s dawn edges to any
     * marker of any timeline.
     */
    default long edgesBetween(Marker marker, long fromExclusive, long toInclusive) {
        if (toInclusive <= fromExclusive) {
            return 0L;
        }
        return repetitionCount(marker, toInclusive) - repetitionCount(marker, fromExclusive);
    }

    /** The latest occurrence at or before this total, if the marker has occurred yet. */
    default OptionalLong previousOccurrence(Marker marker, long totalTicks) {
        long n = repetitionCount(marker, totalTicks);
        if (n <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(marker.ticks() + (n - 1) * (long) marker.periodTicks().orElse(0));
    }

    /** The first occurrence strictly after this total, if there is one (a marker with no period occurs once). */
    default OptionalLong nextOccurrence(Marker marker, long totalTicks) {
        long n = repetitionCount(marker, totalTicks);
        if (!marker.periodic()) {
            return n == 0 ? OptionalLong.of(marker.ticks()) : OptionalLong.empty();
        }
        return OptionalLong.of(marker.ticks() + n * (long) marker.periodTicks().getAsInt());
    }
}
