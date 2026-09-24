package mycelium.harness;

import mycelium.random_seeded.v1.model.SeededRandom;

/**
 * Deterministic randomness for property-style fixtures. Kernels must never use wall-clock or unseeded
 * randomness; fixtures that need variety draw from here so a failure is reproducible from its seed.
 *
 * <p>The draws are random.seeded@1's {@link SeededRandom} (SplitMix64, identical to {@code java.util.SplittableRandom}),
 * so a failure's seed reproduces on every Java a target runs on (docs/eras.md). The harness is compiled with that model
 * ({@code harness/sources.txt}).
 */
public final class Seeded {
    private final SeededRandom random;

    public Seeded(long seed) {
        this.random = new SeededRandom(seed);
    }

    public long seed() {
        return random.seed();
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
