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

/** A peek that answers the declared initial value for a value that is absent, without creating it: absence and the default are one answer. */
public final class PeekReadsDefault<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> peek(Slot<T> slot, HostRef ref) {
        Reading<T> found = super.peek(slot, ref);
        return found.isAbsent() ? new Reading.Present<T>(slot.initial()) : found;
    }
}
