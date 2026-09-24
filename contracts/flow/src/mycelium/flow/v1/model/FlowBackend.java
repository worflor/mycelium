package mycelium.flow.v1.model;

import java.util.Set;

public interface FlowBackend {
    Set<Trait> traits();

    long available(ResourceKey resource);

    long admissibleCapacity(ResourceKey resource);

    long extract(ResourceKey resource, long amount, FlowTransaction tx);

    long insert(ResourceKey resource, long amount, FlowTransaction tx);

    default void seed(ResourceKey resource, long amount) {
        throw new UnsupportedOperationException("seeding not supported by this backend");
    }
}
