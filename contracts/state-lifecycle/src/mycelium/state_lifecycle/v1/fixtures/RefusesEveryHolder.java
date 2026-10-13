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

/** Answers every attempt to create or write an exclusive slot with Held, as if some other host always had it: the value can never come to exist. */
public final class RefusesEveryHolder<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> materialize(Slot<T> slot, HostRef ref, Tx tx) {
        return slot.exclusive() && resolve(ref).isPresent() ? new Reading.Held<T>(ref.host()) : super.materialize(slot, ref, tx);
    }

    @Override
    public <T> Reading<T> write(Slot<T> slot, HostRef ref, T value, Tx tx) {
        return slot.exclusive() && resolve(ref).isPresent() ? new Reading.Held<T>(ref.host()) : super.write(slot, ref, value, tx);
    }
}
