package mycelium.random_seeded.v1.model;

/**
 * Seeded randomness (random.seeded@1): a stream of draws fixed entirely by its seed. The bounded draws are defined
 * here, once, over {@link #nextInt()} and {@link #nextLong()}, so every implementation agrees on what "a number below
 * n" means (SPEC.md): unbiased rejection sampling, the same algorithm {@code java.util.SplittableRandom} uses.
 */
public interface RandomSource {
    /** 32 uniformly random bits. */
    int nextInt();

    /** 64 uniformly random bits. */
    long nextLong();

    /** Uniform in {@code [0, bound)}; {@code bound} must be positive. */
    default int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        int r = nextInt();
        int m = bound - 1;
        if ((bound & m) == 0) {
            return r & m;
        }
        for (int u = r >>> 1; u + m - (r = u % bound) < 0; u = nextInt() >>> 1) {
            // rejected: u fell in the incomplete last block, which would bias the low values
        }
        return r;
    }

    /** Uniform in {@code [origin, bound)}; {@code origin} must be below {@code bound}. */
    default int nextInt(int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin: " + origin + " >= " + bound);
        }
        int r = nextInt();
        int n = bound - origin;
        int m = n - 1;
        if ((n & m) == 0) {
            return (r & m) + origin;
        }
        if (n > 0) {
            for (int u = r >>> 1; u + m - (r = u % n) < 0; u = nextInt() >>> 1) {
                // rejected, as above
            }
            return r + origin;
        }
        while (r < origin || r >= bound) {
            r = nextInt(); // the range is wider than an int's half: draw until inside
        }
        return r;
    }

    /** Uniform in {@code [0, 1)}, 53 bits. */
    default double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** One fair bit. */
    default boolean nextBoolean() {
        return nextInt() < 0;
    }
}
