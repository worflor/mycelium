package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.FlowTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The reference seam from a {@link NativeFluidStore} (the loader's unit) to a {@link Store} in thousandths of a bucket,
 * converting exactly (law S5). A request becomes the native amount of its whole steps; what the native store reports is
 * read as the whole thousandths inside it, the rest staying where it is; and what the native store actually moves must be a
 * whole number of steps, or the seam releases it (its own nested scope aborts) and moves nothing. A remainder is refused,
 * never rounded: no droplet is created or destroyed at the seam.
 */
public final class Fluids {
    private Fluids() {
    }

    public static Store face(NativeFluidStore store) {
        return new View(Objects.requireNonNull(store, "store"));
    }

    private static final class View implements Store {
        private final NativeFluidStore store;
        private final FluidUnit unit;

        View(NativeFluidStore store) {
            this.store = store;
            this.unit = Objects.requireNonNull(store.unit(), "unit");
        }

        @Override
        public StoreAddress address() {
            return store.address();
        }

        @Override
        public Kind kind() {
            return Kind.FLUID;
        }

        @Override
        public boolean present() {
            return store.present();
        }

        private boolean usable(Goods goods, long max) {
            return present() && goods != null && goods.kind() == Kind.FLUID && max > 0;
        }

        @Override
        public List<Holding> contents() {
            if (!present()) {
                return List.of();
            }
            List<Holding> out = new ArrayList<>();
            for (Holding h : store.contents()) {
                long whole = unit.wholeDeclared(h.amount());
                if (whole > 0) {
                    out.add(new Holding(h.goods(), whole));
                }
            }
            return out;
        }

        @Override
        public long simulateExtract(Goods goods, long max) {
            return simulate(goods, max, true);
        }

        @Override
        public long simulateInsert(Goods goods, long max) {
            return simulate(goods, max, false);
        }

        private long simulate(Goods goods, long max, boolean out) {
            if (!usable(goods, max)) {
                return 0;
            }
            long asked = unit.exactAtMost(max);
            if (asked <= 0) {
                return 0;
            }
            long nativeAsked = unit.toNative(asked).getAsLong();
            long nativeAnswer = out ? store.simulateExtract(goods, nativeAsked) : store.simulateInsert(goods, nativeAsked);
            return Math.min(asked, unit.wholeDeclared(nativeAnswer));
        }

        @Override
        public long extract(Goods goods, long max, FlowTransaction tx) {
            return move(goods, max, Objects.requireNonNull(tx, "tx"), true);
        }

        @Override
        public long insert(Goods goods, long max, FlowTransaction tx) {
            return move(goods, max, Objects.requireNonNull(tx, "tx"), false);
        }

        private long move(Goods goods, long max, FlowTransaction tx, boolean out) {
            if (!usable(goods, max)) {
                return 0;
            }
            long asked = unit.exactAtMost(max);
            if (asked <= 0) {
                return 0;
            }
            long nativeAsked = unit.toNative(asked).getAsLong();
            FlowTransaction scope = tx.openNested();
            long moved = out ? store.extract(goods, nativeAsked, scope) : store.insert(goods, nativeAsked, scope);
            if (moved <= 0 || moved > nativeAsked || unit.toDeclared(moved).isEmpty()) {
                scope.abort();
                return 0;
            }
            scope.commit();
            return unit.toDeclared(moved).getAsLong();
        }
    }
}
