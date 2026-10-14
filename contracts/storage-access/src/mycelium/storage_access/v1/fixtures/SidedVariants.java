package mycelium.storage_access.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Holding;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.SlottedContainer;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Sided adapters that each get one thing wrong: the reference {@code Sided} adapter's algorithm, copied, with one flaw
 * switched on, so each differs from the reference in exactly the way its name says.
 */
public final class SidedVariants {
    private SidedVariants() {
    }

    private enum Flaw {
        /** Every slot for every face, and no per-goods check: a face reaches what its side forbids. */
        SIDELESS,
        /** The face's slot list, but neither {@code canPlace} nor {@code canTake}: a plate's shelf filled by a machine. */
        SLOT_LIST_ONLY,
        /** An extraction's undo puts the goods back into the first slot that takes them, not the slot they left. */
        COMPACTING_UNDO,
        /** Simulates an extraction by doing it in a transaction of its own, which it commits. */
        COMMITTING_SIMULATION,
        /** Fills a slot to the container's stack limit, ignoring the goods' own (sixteen ender pearls). */
        OVERSTACKING,
        /** Inserts outside the transaction: the insertion registers no undo, so it survives a release. */
        NON_PARTICIPATING_INSERT,
        /** Never asks whether the container still stands: a handle keeps working after its store vanished. */
        STALE_HANDLE
    }

    public static BiFunction<SlottedContainer, StoreAddress, Store> sideless() {
        return (c, a) -> new View(c, a, Flaw.SIDELESS);
    }

    public static BiFunction<SlottedContainer, StoreAddress, Store> slotListOnly() {
        return (c, a) -> new View(c, a, Flaw.SLOT_LIST_ONLY);
    }

    public static BiFunction<SlottedContainer, StoreAddress, Store> compactingUndo() {
        return (c, a) -> new View(c, a, Flaw.COMPACTING_UNDO);
    }

    public static BiFunction<SlottedContainer, StoreAddress, Store> committingSimulation() {
        return (c, a) -> new View(c, a, Flaw.COMMITTING_SIMULATION);
    }

    public static BiFunction<SlottedContainer, StoreAddress, Store> overstacking() {
        return (c, a) -> new View(c, a, Flaw.OVERSTACKING);
    }

    public static BiFunction<SlottedContainer, StoreAddress, Store> nonParticipatingInsert() {
        return (c, a) -> new View(c, a, Flaw.NON_PARTICIPATING_INSERT);
    }

    public static BiFunction<SlottedContainer, StoreAddress, Store> staleHandle() {
        return (c, a) -> new View(c, a, Flaw.STALE_HANDLE);
    }

    private static final class View implements Store {
        private final SlottedContainer container;
        private final StoreAddress address;
        private final Flaw flaw;

        View(SlottedContainer container, StoreAddress address, Flaw flaw) {
            this.container = container;
            this.address = address;
            this.flaw = flaw;
        }

        @Override
        public StoreAddress address() {
            return address;
        }

        @Override
        public Kind kind() {
            return Kind.ITEM;
        }

        @Override
        public boolean present() {
            return container.present();
        }

        private boolean standing() {
            return flaw == Flaw.STALE_HANDLE || container.present();
        }

        private List<Integer> reach() {
            Set<Integer> out = new LinkedHashSet<>();
            if (flaw == Flaw.SIDELESS) {
                for (int i = 0; i < container.size(); i++) {
                    out.add(i);
                }
                return new ArrayList<>(out);
            }
            for (int slot : container.slotsFor(address.face())) {
                if (slot >= 0 && slot < container.size()) {
                    out.add(slot);
                }
            }
            return new ArrayList<>(out);
        }

        private boolean checksGoods() {
            return flaw != Flaw.SIDELESS && flaw != Flaw.SLOT_LIST_ONLY;
        }

        private boolean mayTake(int slot, Goods goods) {
            return !checksGoods() || container.canTake(slot, goods, address.face());
        }

        private boolean mayPlace(int slot, Goods goods) {
            return !checksGoods() || container.canPlace(slot, goods, address.face());
        }

        private long limit(int slot, Goods goods) {
            if (flaw == Flaw.OVERSTACKING && container instanceof MemoryContainer m) {
                return m.stackLimit();
            }
            return container.limit(slot, goods);
        }

        private boolean usable(Goods goods, long max) {
            return standing() && goods != null && goods.kind() == Kind.ITEM && max > 0;
        }

        @Override
        public List<Holding> contents() {
            if (!standing()) {
                return List.of();
            }
            Map<Goods, Long> sum = new LinkedHashMap<>();
            for (int slot : reach()) {
                Optional<Holding> h = container.at(slot);
                if (h.isPresent() && mayTake(slot, h.get().goods())) {
                    sum.merge(h.get().goods(), h.get().amount(), Long::sum);
                }
            }
            List<Holding> out = new ArrayList<>();
            sum.forEach((g, n) -> out.add(new Holding(g, n)));
            return out;
        }

        private long takeable(int slot, Goods goods) {
            Optional<Holding> h = container.at(slot);
            return h.isPresent() && h.get().goods().equals(goods) && mayTake(slot, goods) ? h.get().amount() : 0;
        }

        private long room(int slot, Goods goods) {
            if (!mayPlace(slot, goods)) {
                return 0;
            }
            Optional<Holding> h = container.at(slot);
            long limit = limit(slot, goods);
            if (h.isEmpty()) {
                return Math.max(0, limit);
            }
            return h.get().goods().equals(goods) ? Math.max(0, limit - h.get().amount()) : 0;
        }

        @Override
        public long simulateExtract(Goods goods, long max) {
            if (flaw == Flaw.COMMITTING_SIMULATION) {
                FlowTransaction own = FlowTransaction.open();
                long n = extract(goods, max, own);
                own.commit();
                return n;
            }
            if (!usable(goods, max)) {
                return 0;
            }
            long total = 0;
            for (int slot : reach()) {
                total += Math.min(max - total, takeable(slot, goods));
                if (total == max) {
                    break;
                }
            }
            return total;
        }

        @Override
        public long simulateInsert(Goods goods, long max) {
            if (!usable(goods, max)) {
                return 0;
            }
            long total = 0;
            for (int slot : reach()) {
                total += Math.min(max - total, room(slot, goods));
                if (total == max) {
                    break;
                }
            }
            return total;
        }

        @Override
        public long extract(Goods goods, long max, FlowTransaction tx) {
            if (!usable(goods, max)) {
                return 0;
            }
            long total = 0;
            for (int slot : reach()) {
                long take = Math.min(max - total, takeable(slot, goods));
                if (take > 0) {
                    Optional<Holding> before = container.at(slot);
                    long left = before.get().amount() - take;
                    if (flaw == Flaw.COMPACTING_UNDO) {
                        tx.registerUndo(() -> giveBack(goods, take));
                    } else {
                        tx.registerUndo(() -> container.put(slot, before));
                    }
                    container.put(slot, left > 0 ? Optional.of(new Holding(goods, left)) : Optional.empty());
                    total += take;
                }
                if (total == max) {
                    break;
                }
            }
            return total;
        }

        /** The compacting undo: back into the first slot holding the goods or empty, whatever slot they came from. */
        private void giveBack(Goods goods, long amount) {
            long left = amount;
            for (int slot = 0; slot < container.size() && left > 0; slot++) {
                Optional<Holding> h = container.at(slot);
                if (h.isEmpty() || h.get().goods().equals(goods)) {
                    long now = h.map(Holding::amount).orElse(0L);
                    long put = Math.min(left, container.limit(slot, goods) - now);
                    if (put > 0) {
                        container.put(slot, Optional.of(new Holding(goods, now + put)));
                        left -= put;
                    }
                }
            }
        }

        @Override
        public long insert(Goods goods, long max, FlowTransaction tx) {
            if (!usable(goods, max)) {
                return 0;
            }
            long total = 0;
            for (int slot : reach()) {
                long put = Math.min(max - total, room(slot, goods));
                if (put > 0) {
                    Optional<Holding> before = container.at(slot);
                    long now = before.map(Holding::amount).orElse(0L) + put;
                    if (flaw != Flaw.NON_PARTICIPATING_INSERT) {
                        tx.registerUndo(() -> container.put(slot, before));
                    }
                    container.put(slot, Optional.of(new Holding(goods, now)));
                    total += put;
                }
                if (total == max) {
                    break;
                }
            }
            return total;
        }
    }
}
