package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.Effect;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.StandardCrossings;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;

/** Catches whatever goes wrong while applying a plan and carries on, as a handler that logs and moves on does: the caller never learns the crossing failed. */
public final class SwallowsFailures implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        return StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        try {
            StandardCrossings.INSTANCE.apply(plan, store, tx);
        } catch (RuntimeException ignored) {
            // logged, in a real handler, and forgotten
        }
    }
}
