package mycelium.agent_link.v1.fixtures;

/** Reconnect schedules that each break one backoff law (SPEC.md, rejection matrix). Each is (attempt, u) -> delay. */
public final class BadBackoffs {
    private BadBackoffs() {
    }

    /** {@code base << attempt}, then capped: the shift wraps and goes negative on a long outage. */
    public static long overflow(long base, long cap, int attempt, double u) {
        long c = Math.min(cap, base << attempt);
        return c / 2 + (long) (u * (c - c / 2));
    }

    /** Gives up after 20 attempts (a negative delay means "stop"). */
    public static long givesUp(long base, long cap, int attempt, double u) {
        if (attempt > 20) {
            return -1;
        }
        long c = Math.min(cap, base << Math.min(attempt, 20));
        return Math.max(1, c / 2 + (long) (u * (c - c / 2)));
    }

    /** Doubles without a ceiling. */
    public static long uncapped(long base, long cap, int attempt, double u) {
        long c = base << Math.min(attempt, 40);
        return Math.max(1, c / 2 + (long) (u * (c - c / 2)));
    }

    /** Starts at the cap: a blip costs the full wait. */
    public static long slowStart(long base, long cap, int attempt, double u) {
        return Math.max(1, cap / 2 + (long) (u * (cap - cap / 2)));
    }
}
