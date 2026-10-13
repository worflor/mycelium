package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.Effect;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.StandardCrossings;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Releases a value that only moves to the next incarnation (a KEEP at a succession): what it owns is let go though the value lives on. */
public final class ReleasesMoved implements Crossings {
    @Override
    public <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        return StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
    }

    /** A value that is about to cease (or move), with its slot. */
    private record Going(Slot<?> slot, Object value) {
    }

    private static <H> List<Going> going(Plan<H> plan, HostStore<H> store, boolean carried) {
        List<Going> out = new ArrayList<>();
        for (Effect e : plan.effects()) {
            if (!(e instanceof Effect.OfSlot s) || !s.slot().releases()) {
                continue;
            }
            Effect.OfSlot.Op op = s.op();
            boolean match = carried ? op == Effect.OfSlot.Op.CARRY
                    : op == Effect.OfSlot.Op.DROP || op == Effect.OfSlot.Op.RELEASE || op == Effect.OfSlot.Op.RESET;
            if (match) {
                Optional<Object> cell = store.cell(carried ? s.from() : s.to(), s.slot().id());
                cell.ifPresent(v -> out.add(new Going(s.slot(), v)));
            }
        }
        return out;
    }

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        List<Going> moved = going(plan, store, true);
        StandardCrossings.INSTANCE.apply(plan, store, tx);
        tx.onOuterCommit(() -> moved.forEach(g -> g.slot().releaseValue(g.value())));
    }
}
