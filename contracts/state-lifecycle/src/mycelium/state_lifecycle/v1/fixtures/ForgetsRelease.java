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


/** Lets go of nothing: the value is removed from the host and what it owned in the world stays, as a hand-written clear list forgets the panels' bodies. */
public final class ForgetsRelease implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        return StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        for (Effect e : plan.effects()) {
            if (e instanceof Effect.OfSlot s && s.slot().releases() && (s.op() == Effect.OfSlot.Op.DROP || s.op() == Effect.OfSlot.Op.RELEASE
                    || s.op() == Effect.OfSlot.Op.RESET)) {
                store.removeCell(s.to(), s.slot().id()); // the engine then finds nothing to release
            }
        }
        StandardCrossings.INSTANCE.apply(plan, store, tx);
    }
}
