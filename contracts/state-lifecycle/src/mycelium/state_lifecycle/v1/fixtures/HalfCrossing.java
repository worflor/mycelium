package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.StandardCrossings;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;

/** Applies a plan into a transaction of its own that it commits: the caller's abort finds no undo, and earlier effects stay. */
public final class HalfCrossing implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        return StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        Tx scratch = Tx.open();
        StandardCrossings.INSTANCE.apply(plan, store, scratch);
        scratch.commit();
    }
}
