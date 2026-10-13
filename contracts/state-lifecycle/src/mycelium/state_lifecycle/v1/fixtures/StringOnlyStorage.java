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

/** A storage medium that can only keep plain strings and silently ignores any other stored form: a structured component value is lost between a save and the next load. */
public final class StringOnlyStorage<H> extends MemoryHostStore<H> {
    @Override
    public void putStored(HostId host, SlotId slot, Object encoded) {
        if (encoded instanceof String) {
            super.putStored(host, slot, encoded);
        }
    }
}
