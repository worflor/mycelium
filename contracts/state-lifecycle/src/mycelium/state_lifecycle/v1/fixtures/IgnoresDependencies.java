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
import java.util.Comparator;
import java.util.List;

/** Plans the slots of a host in id order and ignores what each declares it depends on: a slot's effect can come before the effect of a slot it needs first. */
public final class IgnoresDependencies implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        Plan<H> real = StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
        List<Effect> out = new ArrayList<>();
        List<Effect.OfSlot> slotEffects = new ArrayList<>();
        for (Effect e : real.effects()) {
            if (e instanceof Effect.OfSlot s) {
                slotEffects.add(s);
            }
        }
        slotEffects.sort(Comparator.comparing(s -> s.slot().id()));
        int next = 0;
        for (Effect e : real.effects()) {
            out.add(e instanceof Effect.OfSlot ? slotEffects.get(next++) : e);
        }
        return new Plan<>(transition, out);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        StandardCrossings.INSTANCE.apply(plan, store, tx);
    }
}
