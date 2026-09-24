package mycelium.random_seeded.v1.fixtures;

import mycelium.random_seeded.v1.model.RandomSource;
import mycelium.random_seeded.v1.model.SeededRandom;

/** Broken: the right bits, bounded the naive way ({@code bits % bound}), so low values come up more often. */
public final class ModuloBiasedSource implements RandomSource {
    private final SeededRandom bits;

    public ModuloBiasedSource(long seed) {
        this.bits = new SeededRandom(seed);
    }

    @Override
    public int nextInt() {
        return bits.nextInt();
    }

    @Override
    public long nextLong() {
        return bits.nextLong();
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        return (nextInt() >>> 1) % bound;
    }
}
