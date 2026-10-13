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

/** Finds the host for a write by its id and writes to whichever incarnation is live: a closure holding a stale ref writes into the new object. */
public final class WriteThroughHostId<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> write(Slot<T> slot, HostRef ref, T value, Tx tx) {
        Optional<HostRef> now = current(ref.host());
        return now.isPresent() ? super.write(slot, now.get(), value, tx) : new Reading.Gone<T>();
    }
}
