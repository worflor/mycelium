package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Trait;

import java.util.Set;

public final class UnqualifiedBackend implements FlowBackend {
    private final MemoryBackend delegate;

    public UnqualifiedBackend(long capacityPerResource) {
        this.delegate = new MemoryBackend(capacityPerResource);
    }

    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.ROLLBACK_PARTICIPATION);
    }

    @Override
    public long available(ResourceKey resource) {
        return delegate.available(resource);
    }

    @Override
    public long admissibleCapacity(ResourceKey resource) {
        return delegate.admissibleCapacity(resource);
    }

    @Override
    public long extract(ResourceKey resource, long amount, FlowTransaction tx) {
        return delegate.extract(resource, amount, tx);
    }

    @Override
    public long insert(ResourceKey resource, long amount, FlowTransaction tx) {
        return delegate.insert(resource, amount, tx);
    }

    @Override
    public void seed(ResourceKey resource, long amount) {
        delegate.seed(resource, amount);
    }
}
