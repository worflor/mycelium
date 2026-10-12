package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.SlotId;

/** A storage port that accepts a write and keeps nothing: a durable value does not survive a save and a load. */
public final class ForgetfulStorage<H> extends MemoryHostStore<H> {
    @Override
    public void putStored(HostId host, SlotId slot, Object encoded) {
        // accepted, and lost
    }
}
