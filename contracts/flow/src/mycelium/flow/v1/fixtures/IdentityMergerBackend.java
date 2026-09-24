package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Trait;

import java.util.Set;

public final class IdentityMergerBackend implements FlowBackend {
    private final long capacity;
    private long total;

    public IdentityMergerBackend(long capacityPerResource) {
        this.capacity = capacityPerResource;
    }

    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.ROLLBACK_PARTICIPATION, Trait.IDENTITY_PRESERVING);
    }

    @Override
    public long available(ResourceKey resource) {
        return total;
    }

    @Override
    public long admissibleCapacity(ResourceKey resource) {
        return Math.max(0, capacity - total);
    }

    @Override
    public long extract(ResourceKey resource, long amount, FlowTransaction tx) {
        long moved = Math.min(amount, total);
        if (moved > 0) {
            total -= moved;
            tx.registerUndo(() -> total += moved);
        }
        return moved;
    }

    @Override
    public long insert(ResourceKey resource, long amount, FlowTransaction tx) {
        long moved = Math.min(amount, admissibleCapacity(resource));
        if (moved > 0) {
            total += moved;
            tx.registerUndo(() -> total -= moved);
        }
        return moved;
    }

    @Override
    public void seed(ResourceKey resource, long amount) {
        total += amount;
    }
}
