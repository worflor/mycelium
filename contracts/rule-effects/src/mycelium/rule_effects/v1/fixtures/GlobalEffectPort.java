package mycelium.rule_effects.v1.fixtures;

import mycelium.rule_effects.v1.model.EffectSpec;
import mycelium.rule_effects.v1.model.EntityId;
import mycelium.rule_effects.v1.model.Tx;

/** Deliberately incorrect: applies every effect to every entity it knows, ignoring scope. */
public final class GlobalEffectPort extends MemoryEffectPort {
    @Override
    public boolean apply(EntityId entity, EffectSpec effect, Tx tx) {
        super.apply(entity, effect, tx);
        for (EntityId other : known()) {
            if (!other.equals(entity)) {
                super.apply(other, effect, tx);
            }
        }
        return true;
    }
}
