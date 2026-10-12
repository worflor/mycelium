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
import java.util.List;

/** Copies an EXCLUSIVE slot's value onto the clone instead of moving it: the token now exists on two hosts. */
public final class CloningExclusive implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        Plan<H> real = StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
        List<Effect> out = new ArrayList<>();
        for (Effect e : real.effects()) {
            if (e instanceof Effect.OfSlot s && s.op() == Effect.OfSlot.Op.MOVE) {
                out.add(new Effect.OfSlot(s.slot(), Effect.OfSlot.Op.COPY, s.from(), s.to()));
            } else {
                out.add(e);
            }
        }
        return new Plan<>(transition, out);
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        StandardCrossings.INSTANCE.apply(plan, store, tx);
    }
}
