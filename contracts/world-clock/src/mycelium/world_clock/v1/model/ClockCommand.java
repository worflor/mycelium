package mycelium.world_clock.v1.model;

/**
 * What {@code /time} can do to one clock, as data: set its ticks, add to them, pause or resume it, change its rate, move
 * it to a marker (by id, resolved against the clock's timelines). A command applies through {@link ClockModel#apply}.
 */
public sealed interface ClockCommand {
    /** Set the clock to this many total ticks; the fraction is dropped. */
    record SetTicks(long totalTicks) implements ClockCommand {
    }

    /** Add (or subtract) ticks; the clock stops at zero and keeps its fraction. */
    record AddTicks(int ticks) implements ClockCommand {
    }

    record Pause() implements ClockCommand {
    }

    record Resume() implements ClockCommand {
    }

    /** Ticks added per server tick while running; fractions carry. */
    record SetRate(float rate) implements ClockCommand {
    }

    /** Move to the marker's next occurrence (see {@link ClockModel#moveTo}). */
    record MoveToMarker(String markerId) implements ClockCommand {
    }
}
