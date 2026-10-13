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

/** Takes any other live host of the slot's scope to be the holder of an exclusive slot, whether or not it holds a value: while another player is online, nobody can be first. */
public final class AnyoneOnlineHolds<H> extends MemoryHostStore<H> {
    private <T> Optional<HostId> anyOtherLive(Slot<T> slot, HostRef ref) {
        for (HostRef live : live()) {
            if (!live.host().equals(ref.host()) && live.host().kind() == slot.scope()) {
                return Optional.of(live.host());
            }
        }
        return Optional.empty();
    }

    @Override
    public <T> Reading<T> materialize(Slot<T> slot, HostRef ref, Tx tx) {
        Optional<HostId> other = slot.exclusive() && resolve(ref).isPresent() ? anyOtherLive(slot, ref) : Optional.<HostId>empty();
        return other.isPresent() ? new Reading.Held<T>(other.get()) : super.materialize(slot, ref, tx);
    }

    @Override
    public <T> Reading<T> write(Slot<T> slot, HostRef ref, T value, Tx tx) {
        Optional<HostId> other = slot.exclusive() && resolve(ref).isPresent() ? anyOtherLive(slot, ref) : Optional.<HostId>empty();
        return other.isPresent() ? new Reading.Held<T>(other.get()) : super.write(slot, ref, value, tx);
    }
}
