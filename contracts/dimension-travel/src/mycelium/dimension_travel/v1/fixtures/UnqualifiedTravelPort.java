package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.Trait;

import java.util.Set;

/** Lacks INVENTORY_PRESERVING; the executor must answer Unsupported before touching it. */
public final class UnqualifiedTravelPort extends ForwardingTravelPort {
    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.POSITION_EXACT);
    }
}
