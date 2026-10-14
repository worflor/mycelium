package mycelium.storage_access.v1.fixtures;

import mycelium.storage_access.v1.model.Fluids;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.NativeFluidStore;
import mycelium.storage_access.v1.model.Sided;
import mycelium.storage_access.v1.model.SlottedContainer;
import mycelium.storage_access.v1.model.StoragePort;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The reference port over a {@link MemoryWorld}: a container is an item store through the sided adapter, a tank a fluid
 * store through the fluid seam, looked up at the address's own level and position and handed the address's face. The
 * adapters are parameters, so a broken adapter runs behind an honest lookup.
 */
public class MemoryPort implements StoragePort {
    /** The two seams a port builds stores with. */
    public record Adapters(BiFunction<SlottedContainer, StoreAddress, Store> sided, Function<NativeFluidStore, Store> fluids) {
        public Adapters {
            Objects.requireNonNull(sided, "sided");
            Objects.requireNonNull(fluids, "fluids");
        }

        public static final Adapters REFERENCE = new Adapters(Sided::face, Fluids::face);

        public Adapters withSided(BiFunction<SlottedContainer, StoreAddress, Store> other) {
            return new Adapters(other, fluids);
        }

        public Adapters withFluids(Function<NativeFluidStore, Store> other) {
            return new Adapters(sided, other);
        }
    }

    protected final MemoryWorld world;
    protected final Adapters adapters;

    public MemoryPort(MemoryWorld world, Adapters adapters) {
        this.world = Objects.requireNonNull(world, "world");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    public MemoryPort(MemoryWorld world) {
        this(world, Adapters.REFERENCE);
    }

    @Override
    public Set<Kind> kinds() {
        return EnumSet.of(Kind.ITEM, Kind.FLUID);
    }

    @Override
    public Optional<Store> find(StoreAddress address, Kind kind) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(kind, "kind");
        return build(world.at(address.level(), address.position()), address, kind);
    }

    /** The store a block offers at an address for a kind, through the adapters. */
    protected Optional<Store> build(Block block, StoreAddress address, Kind kind) {
        if (block == null || !block.present()) {
            return Optional.empty();
        }
        if (block instanceof MemoryContainer container && kind == Kind.ITEM) {
            return Optional.of(adapters.sided().apply(container, address));
        }
        if (block instanceof MemoryTank tank && kind == Kind.FLUID) {
            return Optional.of(adapters.fluids().apply(tank.at(address)));
        }
        return Optional.empty();
    }
}
