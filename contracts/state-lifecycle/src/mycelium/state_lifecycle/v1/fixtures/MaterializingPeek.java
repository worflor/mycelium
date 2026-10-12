package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.Reading;
import mycelium.state_lifecycle.v1.model.Slot;

/** A peek that creates the default when the value is absent: looking at a host changes it. */
public final class MaterializingPeek<H> extends MemoryHostStore<H> {
    @Override
    public <T> Reading<T> peek(Slot<T> slot, HostRef ref) {
        Reading<T> found = super.peek(slot, ref);
        if (found.isAbsent()) {
            putCell(ref, slot.id(), slot.initial());
            return new Reading.Present<>(slot.initial());
        }
        return found;
    }
}
