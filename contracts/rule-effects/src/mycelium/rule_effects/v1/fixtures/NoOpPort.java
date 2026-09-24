package mycelium.rule_effects.v1.fixtures;

import mycelium.rule_effects.v1.model.EffectSpec;
import mycelium.rule_effects.v1.model.EntityId;
import mycelium.rule_effects.v1.model.Tx;

/** Deliberately incorrect: honest inspection, dishonest mutation. Claims success and does nothing. */
public final class NoOpPort extends MemoryEffectPort {
    @Override
    public boolean apply(EntityId entity, EffectSpec effect, Tx tx) {
        return true;
    }

    @Override
    public boolean remove(EntityId entity, String id, Tx tx) {
        return true;
    }
}
