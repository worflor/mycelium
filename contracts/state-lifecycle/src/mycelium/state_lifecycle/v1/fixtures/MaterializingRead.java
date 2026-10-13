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

/** A read that stores the default it hands out (a lazy initialiser): looking at a host with no value gives it one. */
public final class MaterializingRead<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> read(Slot<T> slot, HostRef ref) {
        Reading<T> found = super.peek(slot, ref);
        if (found.isAbsent()) {
            putCell(ref, slot.id(), slot.initial());
            return new Reading.Present<T>(slot.initial());
        }
        return super.read(slot, ref);
    }
}
