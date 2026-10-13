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

/** Counts incarnations across all hosts of the store instead of per host: the first incarnation of a host that appears late is not 1. */
public final class GlobalIncarnationCounter<H> extends MemoryHostStore<H> {
    @Override
    public Incarnation next(HostId id) {
        long highest = 0;
        for (Long n : issued.values()) {
            highest = Math.max(highest, n);
        }
        return new Incarnation(highest + 1);
    }
}
