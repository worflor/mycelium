package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Trait;

import java.util.Set;

/**
 * A destination whose room is an estimate: it reports {@code promised} units of capacity but takes at most
 * {@code capacity}, and says exactly what it took. Not a broken implementation. It is a provider every executor must
 * handle (DESIGN §8.1: an insertion check answers "true otherwise or in doubt"; a promise of room is not room), and the
 * only way the executor's restore path (SPEC §6, "provider insert admits less than extracted") is ever reached.
 */
public final class OverpromisingBackend implements FlowBackend {
    private final MemoryBackend store;
    private final long promised;

    public OverpromisingBackend(long capacity, long promised) {
        this.store = new MemoryBackend(capacity);
        this.promised = promised;
    }

    @Override
    public Set<Trait> traits() {
        return store.traits();
    }

    @Override
    public long available(ResourceKey resource) {
        return store.available(resource);
    }

    /** The estimate: more room than the store has. */
    @Override
    public long admissibleCapacity(ResourceKey resource) {
        return Math.max(0, promised - store.available(resource));
    }

    @Override
    public long extract(ResourceKey resource, long amount, FlowTransaction tx) {
        return store.extract(resource, amount, tx);
    }

    /** The truth: what really fits, and it says so. */
    @Override
    public long insert(ResourceKey resource, long amount, FlowTransaction tx) {
        return store.insert(resource, amount, tx);
    }

    @Override
    public void seed(ResourceKey resource, long amount) {
        store.seed(resource, amount);
    }
}
