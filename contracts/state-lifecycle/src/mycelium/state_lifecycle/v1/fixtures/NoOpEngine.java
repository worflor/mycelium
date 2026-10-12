package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;

import java.util.List;

/** Plans nothing: no host ever begins or ends, nothing is dropped, nothing is saved. */
public final class NoOpEngine implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        return new Plan<>(transition, List.of());
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
    }
}
