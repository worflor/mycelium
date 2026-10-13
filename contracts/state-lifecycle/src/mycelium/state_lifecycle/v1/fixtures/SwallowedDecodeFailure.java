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
import mycelium.state_lifecycle.v1.model.CrossingException;

/** Treats a stored form that does not decode as a nuisance: the failure is caught, the crossing carries on with whatever it had applied, and the caller commits. */
public final class SwallowedDecodeFailure implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        return StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        try {
            StandardCrossings.INSTANCE.apply(plan, store, tx);
        } catch (CrossingException ignored) {
            // the load failed; the rest of the login goes ahead
        }
    }
}
