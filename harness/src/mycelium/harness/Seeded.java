package mycelium.harness;

import java.util.SplittableRandom;

/**
 * Deterministic randomness for property-style fixtures. Kernels must never use wall-clock or unseeded
 * randomness; fixtures that need variety draw from here so a failure is reproducible from its seed.
 */
public final class Seeded {
    private final long seed;
    private final SplittableRandom random;

    public Seeded(long seed) {
        this.seed = seed;
        this.random = new SplittableRandom(seed);
    }

    public long seed() {
        return seed;
    }

    public int nextInt(int boundExclusive) {
        return random.nextInt(boundExclusive);
    }

    public int nextInt(int originInclusive, int boundExclusive) {
        return random.nextInt(originInclusive, boundExclusive);
    }

    public long nextLong() {
        return random.nextLong();
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }
}
