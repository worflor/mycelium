package mycelium.random_seeded.v1.harness;

import mycelium.harness.Check;
import mycelium.random_seeded.v1.model.RandomSource;

import java.util.function.LongFunction;

/** random.seeded@1: the laws of seeded randomness (SPEC.md). */
public final class RandomSeededContractTests {
    /** Compatibility behavior is defined by this contract and its SPEC. */
    static final long GOLDEN = 0xc62e86cd3291b5f4L;

    static final long[] SEEDS = {0L, 1L, -1L, 42L, 0x5eedL, Long.MIN_VALUE, Long.MAX_VALUE, 20260924L};
    static final int[] BOUNDS = {1, 2, 3, 7, 16, 100, 1000, 1 << 30, Integer.MAX_VALUE};

    private final LongFunction<RandomSource> sources;

    public RandomSeededContractTests(LongFunction<RandomSource> sources) {
        this.sources = sources;
    }

    public Check run() {
        Check c = new Check();
        t1Golden(c);
        t2SeedIsTheWholeState(c);
        t3Bounds(c);
        t4Unbiased(c);
        t5BadArgumentsRefused(c);
        return c;
    }

    /** Every kind of draw, interleaved, folded to one number. */
    static long script(LongFunction<RandomSource> sources) {
        long sum = 17;
        for (long seed : SEEDS) {
            RandomSource r = sources.apply(seed);
            for (int i = 0; i < 5000; i++) {
                long v;
                switch (i % 6) {
                    case 0 -> v = r.nextLong();
                    case 1 -> v = Double.doubleToLongBits(r.nextDouble());
                    case 2 -> v = r.nextBoolean() ? 1 : 0;
                    case 3 -> v = r.nextInt(BOUNDS[i % BOUNDS.length]);
                    case 4 -> v = r.nextInt(-(i % 50), -(i % 50) + BOUNDS[(i / 7) % BOUNDS.length] / 2 + 1);
                    default -> v = r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
                }
                sum = sum * 31 + v;
            }
        }
        return sum;
    }

    /** T1: a seed draws what SplittableRandom drew from it, draw for draw. */
    private void t1Golden(Check c) {
        c.equalLong(GOLDEN, script(sources), "T1:splitmix-identical");
    }

    /** T2: two streams from one seed draw the same, however they interleave; nothing is shared between streams. */
    private void t2SeedIsTheWholeState(Check c) {
        RandomSource a = sources.apply(99);
        RandomSource b = sources.apply(99);
        RandomSource other = sources.apply(100);
        boolean same = true;
        for (int i = 0; i < 1000; i++) {
            other.nextLong(); // a third stream drawing in between must not disturb either
            long x = a.nextLong();
            other.nextInt();
            same &= x == b.nextLong();
        }
        c.that(same, "T2:same-seed-same-stream");
    }

    /** T3: every bounded draw lands in its range, at the edges too. */
    private void t3Bounds(Check c) {
        RandomSource r = sources.apply(3);
        boolean ok = true;
        for (int i = 0; i < 200000; i++) {
            int b = BOUNDS[i % BOUNDS.length];
            int x = r.nextInt(b);
            int o = -(i % 1000);
            int y = r.nextInt(o, o + 13);
            int z = r.nextInt(Integer.MIN_VALUE + 5, Integer.MAX_VALUE - 5);
            double d = r.nextDouble();
            ok &= x >= 0 && x < b && y >= o && y < o + 13 && z >= Integer.MIN_VALUE + 5 && z < Integer.MAX_VALUE - 5
                    && d >= 0 && d < 1;
        }
        c.that(ok, "T3:in-range");
    }

    /**
     * T4: no value is favoured. A bound of 3 * 2^29 is where naive {@code bits % bound} shows: the lowest third of values
     * would come up twice as often (31 bits over a bound of 1.5 · 2^30), so the lower half of the range would take 62.5% of
     * the draws, not 50%.
     */
    private void t4Unbiased(Check c) {
        RandomSource r = sources.apply(4);
        int bound = 3 << 29;
        int low = 0;
        int n = 200000;
        for (int i = 0; i < n; i++) {
            if (r.nextInt(bound) < bound / 2) {
                low++;
            }
        }
        c.near(0.5, low / (double) n, 0.01, "T4:no-modulo-bias");
        int[] counts = new int[10];
        for (int i = 0; i < n; i++) {
            counts[r.nextInt(10)]++;
        }
        double chi = 0;
        for (int k : counts) {
            chi += (k - n / 10.0) * (k - n / 10.0) / (n / 10.0);
        }
        c.near(0, Math.max(0, chi - 27.88), 0, "T4:uniform-digits"); // chi-squared under p = 0.001 at 9 degrees of freedom
    }

    /** T5: a bound that is not a range is refused, not answered. */
    private void t5BadArgumentsRefused(Check c) {
        RandomSource r = sources.apply(5);
        c.throwsAny(() -> r.nextInt(0), "T5:zero-bound");
        c.throwsAny(() -> r.nextInt(-3), "T5:negative-bound");
        c.throwsAny(() -> r.nextInt(4, 4), "T5:empty-range");
    }
}
