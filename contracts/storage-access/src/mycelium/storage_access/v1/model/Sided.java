package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.FlowTransaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The reference adapter from a {@link SlottedContainer} to the {@link Store} one face reaches (what Fabric's
 * {@code InventoryStorage.of(container, face)} and NeoForge's and Forge's {@code SidedInvWrapper} are). The face reaches the
 * slots {@code slotsFor} gives, in that order; it takes only what {@code canTake} allows and places only what
 * {@code canPlace} allows, into a slot holding the same goods or an empty one, never past the slot's limit. Every write
 * registers an undo that puts the slot back exactly as it was.
 */
public final class Sided {
    private Sided() {
    }

    public static Store face(SlottedContainer container, StoreAddress address) {
        return new View(Objects.requireNonNull(container, "container"), Objects.requireNonNull(address, "address"));
    }

    private static final class View implements Store {
        private final SlottedContainer container;
        private final StoreAddress address;

        View(SlottedContainer container, StoreAddress address) {
            this.container = container;
            this.address = address;
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

        private List<Integer> reach() {
            Set<Integer> out = new LinkedHashSet<>();
            for (int slot : container.slotsFor(address.face())) {
                if (slot >= 0 && slot < container.size()) {
                    out.add(slot);
                }
            }
            return new ArrayList<>(out);
        }

        private boolean usable(Goods goods, long max) {
            return present() && goods != null && goods.kind() == Kind.ITEM && max > 0;
        }

        @Override
        public List<Holding> contents() {
            if (!present()) {
                return List.of();
            }
            Map<Goods, Long> sum = new LinkedHashMap<>();
            for (int slot : reach()) {
                Optional<Holding> h = container.at(slot);
                if (h.isPresent() && container.canTake(slot, h.get().goods(), address.face())) {
                    sum.merge(h.get().goods(), h.get().amount(), Long::sum);
                }
            }
            List<Holding> out = new ArrayList<>();
            sum.forEach((g, n) -> out.add(new Holding(g, n)));
            return out;
        }

        private long takeable(int slot, Goods goods) {
            Optional<Holding> h = container.at(slot);
            return h.isPresent() && h.get().goods().equals(goods) && container.canTake(slot, goods, address.face())
                    ? h.get().amount() : 0;
        }

        private long room(int slot, Goods goods) {
            if (!container.canPlace(slot, goods, address.face())) {
                return 0;
            }
            Optional<Holding> h = container.at(slot);
            long limit = container.limit(slot, goods);
            if (h.isEmpty()) {
                return Math.max(0, limit);
            }
            return h.get().goods().equals(goods) ? Math.max(0, limit - h.get().amount()) : 0;
        }

        @Override
        public long simulateExtract(Goods goods, long max) {
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
            Objects.requireNonNull(tx, "tx");
            if (!usable(goods, max)) {
                return 0;
            }
            long total = 0;
            for (int slot : reach()) {
                long take = Math.min(max - total, takeable(slot, goods));
                if (take > 0) {
                    Optional<Holding> before = container.at(slot);
                    long left = before.get().amount() - take;
                    write(slot, before, left > 0 ? Optional.of(new Holding(goods, left)) : Optional.empty(), tx);
                    total += take;
                }
                if (total == max) {
                    break;
                }
            }
            return total;
        }

        @Override
        public long insert(Goods goods, long max, FlowTransaction tx) {
            Objects.requireNonNull(tx, "tx");
            if (!usable(goods, max)) {
                return 0;
            }
            long total = 0;
            for (int slot : reach()) {
                long put = Math.min(max - total, room(slot, goods));
                if (put > 0) {
                    Optional<Holding> before = container.at(slot);
                    long now = before.map(Holding::amount).orElse(0L) + put;
                    write(slot, before, Optional.of(new Holding(goods, now)), tx);
                    total += put;
                }
                if (total == max) {
                    break;
                }
            }
            return total;
        }

        private void write(int slot, Optional<Holding> before, Optional<Holding> after, FlowTransaction tx) {
            tx.registerUndo(() -> container.put(slot, before));
            container.put(slot, after);
        }
    }
}
