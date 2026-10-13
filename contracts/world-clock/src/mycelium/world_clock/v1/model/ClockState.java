package mycelium.world_clock.v1.model;

/**
 * One clock's state, as 26.x's {@code ClockState} holds it: whole ticks, the fraction not yet whole ({@code partialTick},
 * in [0, 1)), the rate ticks are added at each server tick (a float, as in the game), and whether the clock is paused.
 *
 * <p>The domain is the states the game can reach: non-negative ticks, a finite fraction in [0, 1), a finite rate that is
 * not negative (a rate of zero is a clock that does not run). Time here is a count of ticks, never a wall clock.
 */
public record ClockState(long totalTicks, float partialTick, float rate, boolean paused) {
    /** The model's ceiling on a rate: the game has none, but past the range of an int its floor saturates and means nothing. */
    public static final float MAX_RATE = 1.0E9F;

    public ClockState {
        if (totalTicks < 0) {
            throw new IllegalArgumentException("total ticks cannot be negative: " + totalTicks);
        }
        if (!(partialTick >= 0.0F && partialTick < 1.0F)) {
            throw new IllegalArgumentException("partial tick must be in [0, 1): " + partialTick);
        }
        if (!(rate >= 0.0F) || rate > MAX_RATE) {
            throw new IllegalArgumentException("rate must be in [0, " + MAX_RATE + "]: " + rate);
        }
    }

    /** A new clock as the game starts one: tick zero, rate one, running. */
    public static ClockState start() {
        return new ClockState(0L, 0.0F, 1.0F, false);
    }

    /** The old game's one clock at a day time: rate one, running. */
    public static ClockState at(long totalTicks) {
        return new ClockState(totalTicks, 0.0F, 1.0F, false);
    }

    public ClockState withTotalTicks(long ticks) {
        return new ClockState(ticks, partialTick, rate, paused);
    }

    public ClockState withPartialTick(float fraction) {
        return new ClockState(totalTicks, fraction, rate, paused);
    }

    public ClockState withRate(float newRate) {
        return new ClockState(totalTicks, partialTick, newRate, paused);
    }

    public ClockState withPaused(boolean isPaused) {
        return new ClockState(totalTicks, partialTick, rate, isPaused);
    }
}
