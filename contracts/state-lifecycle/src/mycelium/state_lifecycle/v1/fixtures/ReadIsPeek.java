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

/** A read that is a peek: a value that is absent reads as absent, with no effective default. */
public final class ReadIsPeek<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> read(Slot<T> slot, HostRef ref) {
        return peek(slot, ref);
    }
}
