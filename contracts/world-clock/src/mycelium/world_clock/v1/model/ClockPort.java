package mycelium.world_clock.v1.model;

import java.util.Optional;

/**
 * The seam a bridge implements (decision 0016): what a platform's clocks must answer and do, so the same laws can be run
 * against the game. A native bridge maps it onto 26.x's clock manager; an emulated one keeps the clocks in saved data and
 * evaluates timelines with a {@link ClockModel}. {@code fixtures/MemoryClockPort} is the in-memory reference.
 *
 * <p>The port holds states; the model holds the arithmetic. A port never invents semantics a {@link ClockModel} does
 * not have. It is server-side; a client's side is {@link ClientClocks}.
 */
public interface ClockPort {

    /** The world's declaration: clocks, timelines, dimensions' default clocks. */
    ClockWorld world();

    /** The server's game time: ticks since the world began, which a sync is stamped with. */
    long gameTime();

    /** A clock's state. An unknown clock is an {@link IllegalArgumentException}. */
    ClockState state(ClockId clock);

    /** Applies a command to a clock; a marker id is found among the clock's timelines. */
    Applied apply(ClockId clock, ClockCommand command);

    /** One server tick: game time advances, and every clock advances by the model unless {@code advanceTime} is off. */
    void tick(boolean advanceTime);

    /**
     * A dimension's players sleep through the night: its default clock moves to the wake marker (when {@code advanceTime}
     * is on, and the clock has the marker). Empty when the dimension has no default clock.
     */
    Optional<Applied> sleep(String dimension, boolean advanceTime);

    /** What a joining client is sent: every clock, stamped with the game time. */
    ClockSync sync(boolean advanceTime);

    /** What clients are sent when one clock changes. */
    ClockSync sync(ClockId clock, boolean advanceTime);
}
