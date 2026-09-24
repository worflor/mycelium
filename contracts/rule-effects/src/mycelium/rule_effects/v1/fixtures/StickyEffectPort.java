package mycelium.rule_effects.v1.fixtures;

import mycelium.rule_effects.v1.model.EntityId;
import mycelium.rule_effects.v1.model.Tx;

/** Deliberately incorrect: remove reports success but leaves the effect active. Violates apply/remove symmetry. */
public final class StickyEffectPort extends MemoryEffectPort {
    @Override
    public boolean remove(EntityId entity, String id, Tx tx) {
        return true;
    }
}
