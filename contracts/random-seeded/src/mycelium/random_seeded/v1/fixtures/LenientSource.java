package mycelium.random_seeded.v1.fixtures;

import mycelium.random_seeded.v1.model.RandomSource;
import mycelium.random_seeded.v1.model.SeededRandom;

/** Broken: answers a bound that is not a range (0 for "below 0", the origin for an empty range) instead of refusing. */
public final class LenientSource implements RandomSource {
    private final SeededRandom inner;

    public LenientSource(long seed) {
        this.inner = new SeededRandom(seed);
    }

    @Override
    public int nextInt() {
        return inner.nextInt();
    }

    @Override
    public long nextLong() {
        return inner.nextLong();
    }

    @Override
    public int nextInt(int bound) {
        return bound <= 0 ? 0 : inner.nextInt(bound);
    }

    @Override
    public int nextInt(int origin, int bound) {
        return origin >= bound ? origin : inner.nextInt(origin, bound);
    }
}
