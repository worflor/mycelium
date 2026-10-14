package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.FlowTransaction;

import java.util.List;

/**
 * One store of one kind, as one face reaches it, in the declared unit (items; thousandths of a bucket). A port hands it out
 * ({@link StoragePort#find}); the platform's seam implements it over the loader's storage, or builds it with the reference
 * adapters ({@link Sided#face} over a vanilla-shaped container, {@link Fluids#face} over a store in a loader's fluid unit).
 *
 * <p>What every store keeps (SPEC §4): reading and simulating change nothing (S2); a mutation happens only inside an
 * {@code inventory.transfer@1} {@link FlowTransaction} and registers its exact undo there (S3); the face reaches only the
 * slots its side allows (S4); fluids move only in whole thousandths (S5); once the store has vanished the handle reads and
 * moves nothing (S6); nothing is held past its limit (S7).
 */
public interface Store {
    /** Where this store was found, with the face it answers for. */
    StoreAddress address();

    Kind kind();

    /** False once the store at the address is gone (broken, replaced, unloaded); never true again for this handle. */
    boolean present();

    /** What this face may take, aggregated per goods in first-seen order. Mutates nothing. */
    List<Holding> contents();

    /** How much of {@code goods}, at most {@code max}, this face would give now. Mutates nothing. */
    long simulateExtract(Goods goods, long max);

    /** How much of {@code goods}, at most {@code max}, this face would take now. Mutates nothing. */
    long simulateInsert(Goods goods, long max);

    /** Takes at most {@code max}; returns what it took. Every change registers its exact undo on {@code tx}. */
    long extract(Goods goods, long max, FlowTransaction tx);

    /** Puts at most {@code max}; returns what it put. Every change registers its exact undo on {@code tx}. */
    long insert(Goods goods, long max, FlowTransaction tx);
}
