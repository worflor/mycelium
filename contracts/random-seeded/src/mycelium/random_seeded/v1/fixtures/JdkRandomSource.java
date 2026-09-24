package mycelium.random_seeded.v1.fixtures;

import mycelium.random_seeded.v1.model.RandomSource;

import java.util.Random;

/** Broken: java.util.Random is seeded too, but it is a different stream; every old seed would mean a new world. */
public final class JdkRandomSource implements RandomSource {
    private final Random random;

    public JdkRandomSource(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public int nextInt() {
        return random.nextInt();
    }

    @Override
    public long nextLong() {
        return random.nextLong();
    }
}
