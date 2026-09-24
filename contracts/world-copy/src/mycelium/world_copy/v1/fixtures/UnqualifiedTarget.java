package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.Trait;

import java.util.Set;

/** Lacks BLOCK_ENTITY_CONTROL; the executor must answer Unsupported before touching it. */
public final class UnqualifiedTarget extends ForwardingTarget {
    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.ROLLBACK_PARTICIPATION);
    }
}
