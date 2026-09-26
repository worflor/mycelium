package mycelium.agent_link.v1.model;

/**
 * Reconnect delays (SPEC.md B1-B3, P4): an envelope doubling from {@code baseMs} to {@code capMs}, and a delay drawn
 * from its upper half by a caller-supplied uniform {@code u} in [0, 1) (the model draws no randomness). Defined for every
 * attempt: a link never gives up.
 */
public record Backoff(long baseMs, long capMs) {
    public Backoff {
        if (baseMs < 1 || capMs < baseMs) {
            throw new IllegalArgumentException("1 <= baseMs <= capMs");
        }
    }

    /** 500 ms doubling to 30 s. */
    public static Backoff defaults() {
        return new Backoff(500, 30_000);
    }

    /** The largest delay attempt {@code attempt} may wait. */
    public long ceiling(int attempt) {
        long c = baseMs;
        for (int i = 0; i < attempt && c < capMs; i++) {
            c = c > capMs / 2 ? capMs : c * 2;
        }
        return Math.min(c, capMs);
    }

    /** The delay before reconnect attempt {@code attempt} (0 is the first retry). */
    public long delay(int attempt, double u) {
        long c = ceiling(Math.max(0, attempt));
        double unit = u < 0 || u >= 1 || Double.isNaN(u) ? 0.5 : u;
        long d = c / 2 + (long) (unit * (c - c / 2));
        return Math.max(1, Math.min(c, d));
    }
}
