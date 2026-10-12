package mycelium.state_lifecycle.v1.model;

import java.util.Optional;

/** The four accesses, over a store's primitives. Package-private: reached through {@link HostStore}'s defaults. */
final class Access {
    private Access() {
    }

    static <T> Reading<T> peek(HostStore<?> store, Slot<T> slot, HostRef ref) {
        requireScope(slot, ref);
        if (!store.resolve(ref).isPresent()) {
            return new Reading.Gone<>();
        }
        Optional<Object> cell = store.cell(ref, slot.id());
        return cell.isPresent() ? new Reading.Present<>(slot.cast(cell.get())) : new Reading.Absent<>();
    }

    static <T> Reading<T> read(HostStore<?> store, Slot<T> slot, HostRef ref) {
        requireScope(slot, ref);
        if (!store.resolve(ref).isPresent()) {
            return new Reading.Gone<>();
        }
        Optional<Object> cell = store.cell(ref, slot.id());
        return new Reading.Present<>(cell.isPresent() ? slot.cast(cell.get()) : slot.initial());
    }

    static <T> Reading<T> materialize(HostStore<?> store, Slot<T> slot, HostRef ref, Tx tx) {
        requireScope(slot, ref);
        if (!store.resolve(ref).isPresent()) {
            return new Reading.Gone<>();
        }
        Optional<Object> cell = store.cell(ref, slot.id());
        if (cell.isPresent()) {
            return new Reading.Present<>(slot.cast(cell.get()));
        }
        return set(store, slot, ref, slot.initial(), tx);
    }

    static <T> Reading<T> write(HostStore<?> store, Slot<T> slot, HostRef ref, T value, Tx tx) {
        requireScope(slot, ref);
        if (value == null) {
            throw new IllegalArgumentException("a slot never holds null; drop it instead");
        }
        if (!store.resolve(ref).isPresent()) {
            return new Reading.Gone<>();
        }
        return set(store, slot, ref, value, tx);
    }

    private static <T> Reading<T> set(HostStore<?> store, Slot<T> slot, HostRef ref, T value, Tx tx) {
        if (slot.exclusive()) {
            Optional<HostId> other = holderOtherThan(store, slot.id(), ref.host());
            if (other.isPresent()) {
                return new Reading.Held<>(other.get());
            }
        }
        Optional<Object> before = store.cell(ref, slot.id());
        tx.registerUndo(() -> {
            if (before.isPresent()) {
                store.putCell(ref, slot.id(), before.get());
            } else {
                store.removeCell(ref, slot.id());
            }
        });
        store.putCell(ref, slot.id(), value);
        return new Reading.Present<>(value);
    }

    /** A host other than {@code self} that has a value of the slot, live or stored. */
    static Optional<HostId> holderOtherThan(HostStore<?> store, SlotId slot, HostId self) {
        for (HostRef live : store.live()) {
            if (!live.host().equals(self) && store.cell(live, slot).isPresent()) {
                return Optional.of(live.host());
            }
        }
        for (HostId stored : store.storedHosts(slot)) {
            if (!stored.equals(self)) {
                return Optional.of(stored);
            }
        }
        return Optional.empty();
    }

    private static void requireScope(Slot<?> slot, HostRef ref) {
        if (slot.scope() != ref.host().kind()) {
            throw new IllegalArgumentException("slot " + slot.id() + " is " + slot.scope() + "-scoped, but " + ref
                    + " is a " + ref.host().kind());
        }
    }
}
