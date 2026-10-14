package mycelium.storage_access.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.storage_access.v1.model.FluidUnit;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Holding;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.NativeFluidStore;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.List;
import java.util.Objects;

/**
 * A fluid tank in memory, in a loader's unit: one fluid at a time, a capacity, and a quantum it moves in (1 for an ordinary
 * tank; a third of a bucket for a cauldron, which holds levels). Its native face moves whole quanta only, as Fabric's
 * cauldron storage does. Like the loader's own object it does not check whether it still stands: the seam must.
 */
public final class MemoryTank implements Block {
    private final FluidUnit unit;
    private final long quantum;
    private final long capacity;
    private Goods fluid;
    private long amount;
    private boolean present = true;
    private boolean vanishOnWrite;

    public MemoryTank(FluidUnit unit, long quantum, long capacity) {
        this.unit = Objects.requireNonNull(unit);
        this.quantum = quantum;
        this.capacity = capacity;
    }

    /** A Fabric tank: droplets, any amount. */
    public static MemoryTank fabric(long buckets) {
        return new MemoryTank(Catalog.DROPLETS, 1, buckets * Catalog.DROPLETS.perBucket());
    }

    /** A NeoForge or Forge tank: millibuckets, any amount. */
    public static MemoryTank forge(long buckets) {
        return new MemoryTank(Catalog.MILLIBUCKETS, 1, buckets * Catalog.MILLIBUCKETS.perBucket());
    }

    /** A cauldron as Fabric sees it: one bucket in three levels of 27,000 droplets. */
    public static MemoryTank cauldron() {
        return new MemoryTank(Catalog.DROPLETS, Catalog.DROPLETS.perBucket() / 3, Catalog.DROPLETS.perBucket());
    }

    /** Seeds the tank outside any transaction. */
    public MemoryTank with(Goods fluid, long nativeAmount) {
        this.fluid = nativeAmount > 0 ? fluid : null;
        this.amount = nativeAmount;
        return this;
    }

    public FluidUnit unit() {
        return unit;
    }

    public long quantum() {
        return quantum;
    }

    public long amount() {
        return amount;
    }

    public long amountOf(Goods g) {
        return g.equals(fluid) ? amount : 0;
    }

    public Goods fluid() {
        return fluid;
    }

    @Override
    public boolean present() {
        return present;
    }

    @Override
    public void vanish() {
        present = false;
    }

    @Override
    public void vanishOnNextWrite() {
        vanishOnWrite = true;
    }

    @Override
    public void disarm() {
        vanishOnWrite = false;
    }

    @Override
    public Object snapshot() {
        return List.of(String.valueOf(fluid), amount);
    }

    private void set(Goods newFluid, long newAmount) {
        fluid = newAmount > 0 ? newFluid : null;
        amount = newAmount;
        if (vanishOnWrite) {
            vanishOnWrite = false;
            present = false;
        }
    }

    private long whole(long n) {
        return n <= 0 ? 0 : (n / quantum) * quantum;
    }

    long canGive(Goods g, long max) {
        if (g == null || g.kind() != Kind.FLUID || !g.equals(fluid) || max <= 0) {
            return 0;
        }
        return whole(Math.min(max, amount));
    }

    long canTake(Goods g, long max) {
        if (g == null || g.kind() != Kind.FLUID || max <= 0 || (fluid != null && !g.equals(fluid))) {
            return 0;
        }
        return whole(Math.min(max, capacity - amount));
    }

    /** The tank's one native face (a tank is unsided). */
    public NativeFluidStore at(StoreAddress address) {
        return new NativeFluidStore() {
            @Override
            public StoreAddress address() {
                return address;
            }

            @Override
            public boolean present() {
                return present;
            }

            @Override
            public FluidUnit unit() {
                return unit;
            }

            @Override
            public List<Holding> contents() {
                return amount > 0 ? List.of(new Holding(fluid, amount)) : List.of();
            }

            @Override
            public long simulateExtract(Goods g, long maxNative) {
                return canGive(g, maxNative);
            }

            @Override
            public long simulateInsert(Goods g, long maxNative) {
                return canTake(g, maxNative);
            }

            @Override
            public long extract(Goods g, long maxNative, FlowTransaction tx) {
                long n = canGive(g, maxNative);
                if (n > 0) {
                    write(fluid, amount - n, tx);
                }
                return n;
            }

            @Override
            public long insert(Goods g, long maxNative, FlowTransaction tx) {
                long n = canTake(g, maxNative);
                if (n > 0) {
                    write(g, amount + n, tx);
                }
                return n;
            }
        };
    }

    void write(Goods newFluid, long newAmount, FlowTransaction tx) {
        Goods oldFluid = fluid;
        long oldAmount = amount;
        tx.registerUndo(() -> set(oldFluid, oldAmount));
        set(newFluid, newAmount);
    }

    @Override
    public String toString() {
        return "tank(" + fluid + " " + amount + "/" + capacity + " per bucket " + unit.perBucket() + ")";
    }
}
