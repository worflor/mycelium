package mycelium.storage_access.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.storage_access.v1.model.Face;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Holding;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.Sided;
import mycelium.storage_access.v1.model.SlottedContainer;
import mycelium.storage_access.v1.model.StoragePort;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ports that each get the lookup wrong in one way, written as the reference port with that one thing broken. */
public final class PortVariants {
    private PortVariants() {
    }

    /** Finds every store honestly and hands out stores that report honestly and move nothing. */
    public static StoragePort noOp(MemoryWorld world) {
        return new MemoryPort(world) {
            @Override
            public Optional<Store> find(StoreAddress address, Kind kind) {
                return super.find(address, kind).map(PortVariants::inert);
            }
        };
    }

    /** Keeps each handle it has handed out by address and kind, and hands it out again: a broken and replaced store is never seen. */
    public static StoragePort caching(MemoryWorld world) {
        return new MemoryPort(world) {
            private final Map<List<Object>, Optional<Store>> cache = new HashMap<>();

            @Override
            public Optional<Store> find(StoreAddress address, Kind kind) {
                List<Object> key = List.of(address, kind);
                Optional<Store> hit = cache.get(key);
                if (hit != null && hit.isPresent()) {
                    return hit;
                }
                Optional<Store> found = super.find(address, kind);
                cache.put(key, found);
                return found;
            }
        };
    }

    /** Looks every address up in the first level that has a block there, as a port that always asks the overworld. */
    public static StoragePort levelBlind(MemoryWorld world) {
        return new MemoryPort(world) {
            @Override
            public Optional<Store> find(StoreAddress address, Kind kind) {
                return build(world.anywhereAt(address.position()), address, kind);
            }
        };
    }

    /** Passes the toucher's facing instead of the face it touches: the opposite side of the store answers. */
    public static StoragePort mirroredFace(MemoryWorld world) {
        return new MemoryPort(world) {
            @Override
            public Optional<Store> find(StoreAddress address, Kind kind) {
                return super.find(address.at(opposite(address.face())), kind);
            }
        };
    }

    /** Wraps an empty position as an empty item store, as a fallback that turns a missing container into an empty one. */
    public static StoragePort phantom(MemoryWorld world) {
        return new MemoryPort(world) {
            @Override
            public Optional<Store> find(StoreAddress address, Kind kind) {
                Optional<Store> found = super.find(address, kind);
                if (found.isEmpty() && kind == Kind.ITEM) {
                    return Optional.of(Sided.face(EMPTY, address));
                }
                return found;
            }
        };
    }

    static Face opposite(Face face) {
        return switch (face) {
            case DOWN -> Face.UP;
            case UP -> Face.DOWN;
            case NORTH -> Face.SOUTH;
            case SOUTH -> Face.NORTH;
            case WEST -> Face.EAST;
            case EAST -> Face.WEST;
        };
    }

    private static final SlottedContainer EMPTY = new SlottedContainer() {
        @Override
        public boolean present() {
            return true;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public Optional<Holding> at(int slot) {
            return Optional.empty();
        }

        @Override
        public long limit(int slot, Goods goods) {
            return 0;
        }

        @Override
        public int[] slotsFor(Face face) {
            return new int[0];
        }

        @Override
        public boolean canPlace(int slot, Goods goods, Face face) {
            return false;
        }

        @Override
        public boolean canTake(int slot, Goods goods, Face face) {
            return false;
        }

        @Override
        public void put(int slot, Optional<Holding> holding) {
            throw new IllegalStateException("an empty container has no slot " + slot);
        }
    };

    private static Store inert(Store store) {
        return new Store() {
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
                return store.simulateInsert(goods, max);
            }

            @Override
            public long extract(Goods goods, long max, FlowTransaction tx) {
                return 0;
            }

            @Override
            public long insert(Goods goods, long max, FlowTransaction tx) {
                return 0;
            }
        };
    }
}
