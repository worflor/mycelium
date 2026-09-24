package mycelium.random_seeded.v1.model;

/**
 * The reference {@link RandomSource}: SplitMix64, bit-identical to {@code new java.util.SplittableRandom(seed)} (law
 * T1). Written out rather than borrowed because the JDK's class grew into a Java 17 interface that has no Java 8 form,
 * and a seed must mean the same thing on every Java a target runs on (docs/eras.md). Not thread-safe: one per stream.
 */
public final class SeededRandom implements RandomSource {
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private final long seed;
    private long state;

    public SeededRandom(long seed) {
        this.seed = seed;
        this.state = seed;
    }

    /** The seed this stream started from (for reproducing a failure or a world). */
    public long seed() {
        return seed;
    }

    @Override
    public int nextInt() {
        long z = state += GOLDEN_GAMMA;
        z = (z ^ (z >>> 33)) * 0x62a9d9ed799705f5L;
        return (int) (((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32);
    }

    @Override
    public long nextLong() {
        long z = state += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
