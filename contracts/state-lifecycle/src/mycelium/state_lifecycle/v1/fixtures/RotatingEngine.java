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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plans the right effects in an order that depends on how many plans it has made: the same input, a different plan. */
public final class RotatingEngine implements Crossings {
    private int made;

    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        Plan<H> real = StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
        List<Effect> out = new ArrayList<>(real.effects());
        if (!out.isEmpty()) {
            Collections.rotate(out, made++ % out.size());
        }
        return new Plan<>(transition, out);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        StandardCrossings.INSTANCE.apply(plan, store, tx);
    }
}
