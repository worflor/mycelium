package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.Durability;
import mycelium.state_lifecycle.v1.model.Effect;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.Shape;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.StandardCrossings;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;

import java.util.ArrayList;
import java.util.List;

/** Ignores the end of an incarnation for session slots: the host ends and its map entries stay, as a stop-time clear list forgets them. */
public final class ForgetfulEngine implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        Plan<H> real = StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
        if (registry.transitions().get(transition).shape() != Shape.ENDS) {
            return real;
        }
        List<Effect> kept = new ArrayList<>();
        for (Effect e : real.effects()) {
            boolean sessionDrop = e instanceof Effect.OfSlot s && s.slot().durability() == Durability.SESSION;
            if (!sessionDrop) {
                kept.add(e);
            }
        }
        return new Plan<>(transition, kept);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        StandardCrossings.INSTANCE.apply(plan, store, tx);
    }
}
