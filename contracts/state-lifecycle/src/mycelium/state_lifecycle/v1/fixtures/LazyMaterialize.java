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

/** Hands back the initial value from materialize and does not keep it: the host still holds nothing afterwards. */
public final class LazyMaterialize<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> materialize(Slot<T> slot, HostRef ref, Tx tx) {
        Reading<T> found = peek(slot, ref);
        return found.isAbsent() ? new Reading.Present<T>(slot.initial()) : found;
    }
}
