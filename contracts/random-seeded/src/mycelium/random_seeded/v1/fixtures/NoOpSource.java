package mycelium.random_seeded.v1.fixtures;

import mycelium.random_seeded.v1.model.RandomSource;

/**
 * Does nothing: every draw is zero. It is perfectly reproducible (the same seed gives the same stream) and every
 * bounded draw stays in range, which is why it must be in the matrix: T1 says a seed draws what SplittableRandom
 * drew from it, so a source that draws nothing fails.
 */
public final class NoOpSource implements RandomSource {
    public NoOpSource(long seed) {
    }

    @Override
    public int nextInt() {
        return 0;
    }

    @Override
    public long nextLong() {
        return 0;
    }
}
