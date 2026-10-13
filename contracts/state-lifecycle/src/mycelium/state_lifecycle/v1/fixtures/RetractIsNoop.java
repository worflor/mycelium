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

/** Undoing a begin does nothing: after an aborted or failed login the host stays live and its counter stays advanced. */
public final class RetractIsNoop<H> extends MemoryHostStore<H> {
    @Override
    public void retract(HostRef ref) {
        // the undo of a begin is not implemented
    }
}
