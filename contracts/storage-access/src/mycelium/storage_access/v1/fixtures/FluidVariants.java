package mycelium.storage_access.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.storage_access.v1.model.FluidUnit;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Holding;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.NativeFluidStore;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Fluid seams that convert between the loader's unit and thousandths of a bucket inexactly, each in one way. */
public final class FluidVariants {
    private FluidVariants() {
    }

    private enum Flaw {
        /** Converts every native amount to the nearest thousandth: 60 droplets read as one millibucket, and move as 81. */
        ROUNDING,
        /** Converts exactly where it can, but keeps an inexact native move and reports the whole thousandths in it. */
        FLOORING
    }

    public static Function<NativeFluidStore, Store> rounding() {
        return s -> new View(s, Flaw.ROUNDING);
    }

    public static Function<NativeFluidStore, Store> flooring() {
        return s -> new View(s, Flaw.FLOORING);
    }

    private static final class View implements Store {
        private final NativeFluidStore store;
        private final FluidUnit unit;
        private final Flaw flaw;

        View(NativeFluidStore store, Flaw flaw) {
            this.store = store;
            this.unit = store.unit();
            this.flaw = flaw;
        }

        private long declared(long nativeAmount) {
            if (flaw == Flaw.ROUNDING) {
                return Math.round(nativeAmount * (double) FluidUnit.DECLARED_PER_BUCKET / unit.perBucket());
            }
            return unit.wholeDeclared(nativeAmount);
        }

        private long nativeOf(long declared) {
            if (flaw == Flaw.ROUNDING) {
                return Math.round(declared * (double) unit.perBucket() / FluidUnit.DECLARED_PER_BUCKET);
            }
            return unit.toNative(unit.exactAtMost(declared)).getAsLong();
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
                long d = declared(h.amount());
                if (d > 0) {
                    out.add(new Holding(h.goods(), d));
                }
            }
            return out;
        }

        @Override
        public long simulateExtract(Goods goods, long max) {
            if (!usable(goods, max)) {
                return 0;
            }
            long asked = Math.min(max, 1_000_000_000L);
            return Math.min(asked, declared(store.simulateExtract(goods, nativeOf(asked))));
        }

        @Override
        public long simulateInsert(Goods goods, long max) {
            if (!usable(goods, max)) {
                return 0;
            }
            long asked = Math.min(max, 1_000_000_000L);
            return Math.min(asked, declared(store.simulateInsert(goods, nativeOf(asked))));
        }

        @Override
        public long extract(Goods goods, long max, FlowTransaction tx) {
            return move(goods, max, tx, true);
        }

        @Override
        public long insert(Goods goods, long max, FlowTransaction tx) {
            return move(goods, max, tx, false);
        }

        private long move(Goods goods, long max, FlowTransaction tx, boolean out) {
            if (!usable(goods, max)) {
                return 0;
            }
            long nativeAsked = nativeOf(Math.min(max, 1_000_000_000L));
            if (nativeAsked <= 0) {
                return 0;
            }
            FlowTransaction scope = tx.openNested();
            long moved = out ? store.extract(goods, nativeAsked, scope) : store.insert(goods, nativeAsked, scope);
            long reported = declared(moved);
            if (moved <= 0 || reported <= 0) {
                scope.abort();
                return 0;
            }
            scope.commit();
            return reported;
        }
    }
}
