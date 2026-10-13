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

/** A write that registers its undo in a transaction nobody holds: aborting the caller's transaction leaves the written value. */
public final class UnloggedWrite<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> write(Slot<T> slot, HostRef ref, T value, Tx tx) {
        return super.write(slot, ref, value, Tx.open());
    }
}
