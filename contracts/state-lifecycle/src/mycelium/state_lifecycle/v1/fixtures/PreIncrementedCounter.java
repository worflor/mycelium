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

/** Starts a host's incarnation count at 2: the counter is bumped before the first incarnation is handed out, so the first one is not the first. */
public final class PreIncrementedCounter<H> extends MemoryHostStore<H> {
    @Override
    public Incarnation next(HostId id) {
        Incarnation n = super.next(id);
        return issued.containsKey(id) ? n : n.next();
    }
}
