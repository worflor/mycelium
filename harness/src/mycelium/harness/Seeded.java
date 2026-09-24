package mycelium.harness;

/**
 * Deterministic randomness for property-style fixtures. Kernels must never use wall-clock or unseeded
 * randomness; fixtures that need variety draw from here so a failure is reproducible from its seed.
 *
 * <p>SplitMix64, written out rather than borrowed from {@code java.util.SplittableRandom}: the sequence is
 * bit-identical to {@code new SplittableRandom(seed)} (checked by {@code SeededIdentity}), but it is Mycelium's own,
 * so a seed reproduces the same failure on every JVM a target runs on, Java 8 included (docs/eras.md).
 */
public final class Seeded {
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private final long seed;
    private long state;

    public Seeded(long seed) {
        this.seed = seed;
        this.state = seed;
    }

    public long seed() {
        return seed;
    }

    public int nextInt(int boundExclusive) {
        if (boundExclusive <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        int r = nextInt();
        int m = boundExclusive - 1;
        if ((boundExclusive & m) == 0) {
            return r & m;
        }
        for (int u = r >>> 1; u + m - (r = u % boundExclusive) < 0; u = nextInt() >>> 1) {
        }
        return r;
    }

    public int nextInt(int originInclusive, int boundExclusive) {
        if (originInclusive >= boundExclusive) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        int r = nextInt();
        int n = boundExclusive - originInclusive;
        int m = n - 1;
        if ((n & m) == 0) {
            return (r & m) + originInclusive;
        }
        if (n > 0) {
            for (int u = r >>> 1; u + m - (r = u % n) < 0; u = nextInt() >>> 1) {
            }
            return r + originInclusive;
        }
        while (r < originInclusive || r >= boundExclusive) {
            r = nextInt();
        }
        return r;
    }

    public long nextLong() {
        return mix64(next());
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    public boolean nextBoolean() {
        return nextInt() < 0;
    }

    private int nextInt() {
        return mix32(next());
    }

    private long next() {
        return state += GOLDEN_GAMMA;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static int mix32(long z) {
        z = (z ^ (z >>> 33)) * 0x62a9d9ed799705f5L;
        return (int) (((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32);
    }
}
