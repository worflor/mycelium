package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Trait;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MemoryBackend implements FlowBackend {
    private final Map<ResourceKey, Long> contents = new HashMap<>();
    private final long capacityPerResource;

    public MemoryBackend(long capacityPerResource) {
        this.capacityPerResource = capacityPerResource;
    }

    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.ROLLBACK_PARTICIPATION, Trait.IDENTITY_PRESERVING);
    }

    @Override
    public long available(ResourceKey resource) {
        return contents.getOrDefault(resource, 0L);
    }

    @Override
    public long admissibleCapacity(ResourceKey resource) {
        return Math.max(0, capacityPerResource - available(resource));
    }

    @Override
    public long extract(ResourceKey resource, long amount, FlowTransaction tx) {
        long moved = Math.min(amount, available(resource));
        if (moved > 0) {
            contents.merge(resource, -moved, Long::sum);
            tx.registerUndo(() -> contents.merge(resource, moved, Long::sum));
        }
        return moved;
    }

    @Override
    public long insert(ResourceKey resource, long amount, FlowTransaction tx) {
        long moved = Math.min(amount, admissibleCapacity(resource));
        if (moved > 0) {
            contents.merge(resource, moved, Long::sum);
            tx.registerUndo(() -> contents.merge(resource, -moved, Long::sum));
        }
        return moved;
    }

    @Override
    public void seed(ResourceKey resource, long amount) {
        contents.merge(resource, amount, Long::sum);
    }
}
