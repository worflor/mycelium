package mycelium.storage_access.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Holding;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.StoragePort;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Provider behaviours every executor must handle, not broken implementations of this contract (as {@code inventory.transfer@1}'s
 * {@code OverpromisingBackend} is not): a store that promises more room than it has (Fabric's {@code supportsInsertion} is
 * "true otherwise or in doubt"; a simulation is a promise, not room), and a platform that reaches items only.
 */
public final class Providers {
    private Providers() {
    }

    /** The store at {@code at} answers every simulated insertion with up to {@code promise} more than it then takes. */
    public static StoragePort overpromising(StoragePort port, StoreAddress at, long promise) {
        return new StoragePort() {
            @Override
            public Set<Kind> kinds() {
                return port.kinds();
            }

            @Override
            public Optional<Store> find(StoreAddress address, Kind kind) {
                Optional<Store> found = port.find(address, kind);
                if (found.isEmpty() || !address.sameStoreAs(at)) {
                    return found;
                }
                Store store = found.get();
                return Optional.of(new Store() {
                    @Override
                    public StoreAddress address() {
                        return store.address();
                    }

                    @Override
                    public Kind kind() {
                        return store.kind();
                    }

                    @Override
                    public boolean present() {
                        return store.present();
                    }

                    @Override
                    public List<Holding> contents() {
                        return store.contents();
                    }

                    @Override
                    public long simulateExtract(Goods goods, long max) {
                        return store.simulateExtract(goods, max);
                    }

                    @Override
                    public long simulateInsert(Goods goods, long max) {
                        return Math.min(max, store.simulateInsert(goods, max) + promise);
                    }

                    @Override
                    public long extract(Goods goods, long max, FlowTransaction tx) {
                        return store.extract(goods, max, tx);
                    }

                    @Override
                    public long insert(Goods goods, long max, FlowTransaction tx) {
                        return store.insert(goods, max, tx);
                    }
                });
            }
        };
    }

    /** A platform with no fluid storage to reach (vanilla's containers alone, a plugin API). */
    public static StoragePort itemsOnly(StoragePort port) {
        return new StoragePort() {
            @Override
            public Set<Kind> kinds() {
                return EnumSet.of(Kind.ITEM);
            }

            @Override
            public Optional<Store> find(StoreAddress address, Kind kind) {
                return kind == Kind.ITEM ? port.find(address, kind) : Optional.empty();
            }
        };
    }
}
