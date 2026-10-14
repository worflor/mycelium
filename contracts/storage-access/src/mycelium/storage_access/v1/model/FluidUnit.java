package mycelium.storage_access.v1.model;

import java.util.OptionalLong;

/**
 * A unit of fluid, by how many of it make a bucket. The contract's declared unit is {@link #DECLARED}, thousandths of a
 * bucket; each loader keeps fluids in a unit of its own (its row in {@code eras/loaders.json}, {@code fluid.perBucket}:
 * Fabric's droplets are 81,000 to a bucket, NeoForge's and Forge's millibuckets 1,000).
 *
 * <p>The two units meet exactly in steps: {@link #declaredStep()} thousandths are {@link #nativeStep()} native units, the
 * smallest amounts both can say. An amount converts only when it is a whole number of steps; anything else is a remainder,
 * which the seam refuses and never rounds (law S5).
 */
public record FluidUnit(long perBucket) {
    /** Thousandths of a bucket per bucket. */
    public static final long DECLARED_PER_BUCKET = 1000;

    /** The contract's own unit: thousandths of a bucket. */
    public static final FluidUnit DECLARED = new FluidUnit(DECLARED_PER_BUCKET);

    public FluidUnit {
        if (perBucket < 1) {
            throw new IllegalArgumentException("a bucket holds at least one unit");
        }
    }

    private long common() {
        long a = perBucket;
        long b = DECLARED_PER_BUCKET;
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** The smallest declared amount that converts exactly (1 for every unit whose bucket is a multiple of 1000). */
    public long declaredStep() {
        return DECLARED_PER_BUCKET / common();
    }

    /** The native amount {@link #declaredStep()} converts to (81 droplets for one thousandth). */
    public long nativeStep() {
        return perBucket / common();
    }

    /** The native amount of {@code declared} thousandths, when it is exact. */
    public OptionalLong toNative(long declared) {
        if (declared < 0 || declared % declaredStep() != 0) {
            return OptionalLong.empty();
        }
        long steps = declared / declaredStep();
        if (steps > Long.MAX_VALUE / nativeStep()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(steps * nativeStep());
    }

    /** The thousandths a native amount is, when it is exact. */
    public OptionalLong toDeclared(long nativeAmount) {
        if (nativeAmount < 0 || nativeAmount % nativeStep() != 0) {
            return OptionalLong.empty();
        }
        long steps = nativeAmount / nativeStep();
        if (steps > Long.MAX_VALUE / declaredStep()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(steps * declaredStep());
    }

    /** The whole thousandths inside a native amount: what of it can move exactly. The rest stays where it is. */
    public long wholeDeclared(long nativeAmount) {
        if (nativeAmount <= 0) {
            return 0;
        }
        long steps = nativeAmount / nativeStep();
        return steps > Long.MAX_VALUE / declaredStep() ? (Long.MAX_VALUE / declaredStep()) * declaredStep() : steps * declaredStep();
    }

    /** The largest declared amount at most {@code declared} that converts exactly and without overflow. */
    public long exactAtMost(long declared) {
        if (declared <= 0) {
            return 0;
        }
        long steps = Math.min(declared / declaredStep(), Long.MAX_VALUE / nativeStep());
        return steps * declaredStep();
    }
}
