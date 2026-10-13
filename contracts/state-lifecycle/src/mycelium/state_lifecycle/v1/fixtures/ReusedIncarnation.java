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

/** Reuses the number of an ended incarnation when its host comes back (a relog gets the incarnation the last login had): the counter advances only while the host stays live. */
public final class ReusedIncarnation<H> extends MemoryHostStore<H> {
    @Override
    public Incarnation next(HostId id) {
        Long last = issued.get(id);
        if (last == null) {
            return Incarnation.FIRST;
        }
        return live.containsKey(id) ? new Incarnation(last).next() : new Incarnation(last);
    }
}
