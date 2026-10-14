package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.FlowTransaction;

import java.util.List;

/**
 * A fluid store as the loader keeps it, one face of it, in the loader's own unit ({@link #unit()}; Fabric's droplets,
 * NeoForge's and Forge's millibuckets): the platform implements this over the loader's API and {@link Fluids#face} turns it
 * into a {@link Store} in thousandths, converting exactly. The same laws hold as for a store, in native units.
 */
public interface NativeFluidStore {
    StoreAddress address();

    boolean present();

    /** The loader's unit: its {@code fluid.perBucket} row in {@code eras/loaders.json}. */
    FluidUnit unit();

    /** What this face may take, in native units. Mutates nothing. */
    List<Holding> contents();

    long simulateExtract(Goods fluid, long maxNative);

    long simulateInsert(Goods fluid, long maxNative);

    long extract(Goods fluid, long maxNative, FlowTransaction tx);

    long insert(Goods fluid, long maxNative, FlowTransaction tx);
}
