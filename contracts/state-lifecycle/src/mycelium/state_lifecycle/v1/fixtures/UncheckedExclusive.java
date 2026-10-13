package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.Incarnation;
import mycelium.state_lifecycle.v1.model.Reading;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotId;
import mycelium.state_lifecycle.v1.model.Tx;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Creates and writes an exclusive slot without asking whether another host already holds it: the same token can be handed to a second host. */
public final class UncheckedExclusive<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> materialize(Slot<T> slot, HostRef ref, Tx tx) {
        if (!slot.exclusive() || !resolve(ref).isPresent()) {
            return super.materialize(slot, ref, tx);
        }
        Reading<T> found = peek(slot, ref);
        return found instanceof Reading.Present ? found : put(slot, ref, slot.initial(), tx);
    }

    @Override
    public <T> Reading<T> write(Slot<T> slot, HostRef ref, T value, Tx tx) {
        return slot.exclusive() && resolve(ref).isPresent() ? put(slot, ref, value, tx) : super.write(slot, ref, value, tx);
    }

    private <T> Reading<T> put(Slot<T> slot, HostRef ref, T value, Tx tx) {
        Optional<Object> before = cell(ref, slot.id());
        SlotId id = slot.id();
        tx.registerUndo(() -> {
            if (before.isPresent()) {
                putCell(ref, id, before.get());
            } else {
                removeCell(ref, id);
            }
        });
        putCell(ref, id, value);
        return new Reading.Present<T>(value);
    }
}
