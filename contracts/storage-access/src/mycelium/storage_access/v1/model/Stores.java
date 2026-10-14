package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Trait;

import java.util.Objects;
import java.util.Set;

/**
 * A {@link Store} as an {@code inventory.transfer@1} provider, for pairing one store with another provider of that contract
 * (a player's inventory, a machine buffer, an item entity a mod keeps as a provider) in {@code Transfer.execute}. Resources are
 * goods of the store's kind; amounts are the declared unit. The store keeps S3 (every change registers its undo), so it
 * declares {@code ROLLBACK_PARTICIPATION}; it keeps goods apart by their token, so it declares {@code IDENTITY_PRESERVING}.
 * Between two stores, use {@link StorageAccess#move}: {@code Transfer.execute} returns an unaccepted remainder through the
 * source's own insertion, which a sided face may refuse (a furnace's output slot takes nothing from its bottom face).
 */
public final class Stores {
    private Stores() {
    }

    public static FlowBackend backend(Store store) {
        Objects.requireNonNull(store, "store");
        return new FlowBackend() {
            private Goods goods(ResourceKey resource) {
                return new Goods(store.kind(), resource);
            }

            @Override
            public Set<Trait> traits() {
                return Set.of(Trait.ROLLBACK_PARTICIPATION, Trait.IDENTITY_PRESERVING);
            }

            @Override
            public long available(ResourceKey resource) {
                return store.simulateExtract(goods(resource), Long.MAX_VALUE);
            }

            @Override
            public long admissibleCapacity(ResourceKey resource) {
                return store.simulateInsert(goods(resource), Long.MAX_VALUE);
            }

            @Override
            public long extract(ResourceKey resource, long amount, FlowTransaction tx) {
                return store.extract(goods(resource), amount, tx);
            }

            @Override
            public long insert(ResourceKey resource, long amount, FlowTransaction tx) {
                return store.insert(goods(resource), amount, tx);
            }
        };
    }
}
