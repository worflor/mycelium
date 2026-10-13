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

/** Remembers every host id that ever ended and resolves none of its refs afterwards: a relogged player's fresh ref is as dead as the old one. */
public final class TombstonedResolver<H> extends MemoryHostStore<H> {
    private final Set<HostId> endedBefore = new HashSet<>();

    @Override
    public void end(HostRef ref) {
        super.end(ref);
        endedBefore.add(ref.host());
    }

    @Override
    public Optional<H> resolve(HostRef ref) {
        return endedBefore.contains(ref.host()) ? Optional.<H>empty() : super.resolve(ref);
    }
}
