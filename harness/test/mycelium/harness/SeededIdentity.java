package mycelium.harness;

import java.util.SplittableRandom;

/**
 * Seeded (random.seeded@1's SeededRandom) draws exactly what {@code java.util.SplittableRandom} draws, live, so every
 * seed recorded before Seeded owned its algorithm still reproduces. Runs on a modern JDK (it needs the JDK's class to
 * compare against); Seeded itself runs anywhere. {@code run-contracts.sh} runs it first.
 */
public final class SeededIdentity {
    public static void main(String[] args) {
        int draws = 0;
        for (long seed : new long[]{0L, 1L, -1L, 42L, 0x5eedL, Long.MIN_VALUE, Long.MAX_VALUE, 20260924L}) {
            Seeded mine = new Seeded(seed);
            SplittableRandom jdk = new SplittableRandom(seed);
            int[] bounds = {1, 2, 3, 7, 16, 100, 1000, 1 << 30, Integer.MAX_VALUE};
            for (int i = 0; i < 20000; i++) {
                int kind = i % 6;
                boolean same;
                switch (kind) {
                    case 0 -> same = mine.nextLong() == jdk.nextLong();
                    case 1 -> same = Double.compare(mine.nextDouble(), jdk.nextDouble()) == 0;
                    case 2 -> same = mine.nextBoolean() == jdk.nextBoolean();
                    case 3 -> {
                        int b = bounds[i % bounds.length];
                        same = mine.nextInt(b) == jdk.nextInt(b);
                    }
                    case 4 -> {
                        int o = -(i % 50);
                        int b = o + bounds[(i / 7) % bounds.length] / 2 + 1;
                        same = mine.nextInt(o, b) == jdk.nextInt(o, b);
                    }
                    default -> same = mine.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE) == jdk.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
                }
                if (!same) {
                    System.out.println("SeededIdentity: FAIL seed=" + seed + " draw=" + i + " kind=" + kind);
                    System.exit(1);
                }
                draws++;
            }
        }
        System.out.println("SeededIdentity: PASS (" + draws + " draws identical to java.util.SplittableRandom)");
    }
}
