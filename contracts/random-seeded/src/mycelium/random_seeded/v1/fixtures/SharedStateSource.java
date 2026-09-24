package mycelium.random_seeded.v1.fixtures;

import mycelium.random_seeded.v1.model.RandomSource;

/** Broken: the right algorithm with one state for every stream, so any other stream's draw moves yours. */
public final class SharedStateSource implements RandomSource {
    private static long state;

    public SharedStateSource(long seed) {
        state = seed;
    }

    @Override
    public int nextInt() {
        long z = state += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 33)) * 0x62a9d9ed799705f5L;
        return (int) (((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32);
    }

    @Override
    public long nextLong() {
        long z = state += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
