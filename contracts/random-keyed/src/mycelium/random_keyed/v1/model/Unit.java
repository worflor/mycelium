package mycelium.random_keyed.v1.model;

/**
 * Exact units (random.keyed@1, law K5). A unit is 53 bits, {@code [0, 2^53)}, read as {@code u / 2^53} in {@code [0, 1)}:
 * the high 53 bits of a draw's first 8 bytes. Odds are per mille, compared in integer arithmetic with no float, so the
 * same draw is decided the same way on every machine and a recorded draw can be replayed and checked.
 */
public final class Unit {
    /** Bits in a unit. */
    public static final int BITS = 53;
    /** One past the largest unit: {@code 2^53}. */
    public static final long ONE = 1L << BITS;

    private Unit() {
    }

    /** The first 8 bytes of a digest or a MAC, big-endian, as a {@code long}. */
    public static long first64(byte[] digest) {
        if (digest.length < 8) {
            throw new IllegalArgumentException("a digest has at least 8 bytes");
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = v << 8 | (digest[i] & 0xff);
        }
        return v;
    }

    /** The unit of 64 bits: their high 53, {@code bits >>> 11}, in {@code [0, 2^53)}. */
    public static long of(long bits) {
        return bits >>> (64 - BITS);
    }

    /**
     * Whether a unit falls below odds of {@code permille} in a thousand: exactly {@code unit53 * 1000 < permille * 2^53}
     * (both sides fit a {@code long}). {@code below(u, 0)} is never true and {@code below(u, 1000)} always is. Refuses a
     * unit outside {@code [0, 2^53)} and odds outside {@code [0, 1000]}.
     */
    public static boolean below(long unit53, int permille) {
        if (unit53 < 0 || unit53 >= ONE) {
            throw new IllegalArgumentException("a unit is in [0, 2^53): " + unit53);
        }
        if (permille < 0 || permille > 1000) {
            throw new IllegalArgumentException("odds are 0 to 1000 per mille: " + permille);
        }
        return unit53 * 1000 < (long) permille << BITS;
    }
}
