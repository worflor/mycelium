package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Trait;

import java.util.Set;

public final class VoidingPartialBackend implements FlowBackend {
    private final MemoryBackend delegate;

    public VoidingPartialBackend(long capacityPerResource) {
        this.delegate = new MemoryBackend(capacityPerResource);
    }

    @Override
    public Set<Trait> traits() {
        return delegate.traits();
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
        long retained = amount / 2;
        if (retained > 0) {
            delegate.insert(resource, retained, tx);
        }
        return amount;
    }

    @Override
    public void seed(ResourceKey resource, long amount) {
        delegate.seed(resource, amount);
    }
}
