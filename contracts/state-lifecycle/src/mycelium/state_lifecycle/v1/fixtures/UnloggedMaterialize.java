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

/** A materialize that registers its undo in a transaction nobody holds (writes are logged, the lazy creation is not): an abort leaves the created value. */
public final class UnloggedMaterialize<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> materialize(Slot<T> slot, HostRef ref, Tx tx) {
        return super.materialize(slot, ref, Tx.open());
    }
}
