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
import java.util.List;
import mycelium.state_lifecycle.v1.model.StaleHostException;

/** Answers a crossing aimed at a stale incarnation with a quiet no-op instead of refusing it: neither planning nor applying tells the caller the host has moved on. */
public final class SwallowsStaleHosts implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        try {
            return StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
        } catch (StaleHostException e) {
            return new Plan<>(transition, List.of());
        }
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        try {
            StandardCrossings.INSTANCE.apply(plan, store, tx);
        } catch (StaleHostException e) {
            // the host moved on since the plan was made: nothing to do for it, and no one told
        }
    }
}
