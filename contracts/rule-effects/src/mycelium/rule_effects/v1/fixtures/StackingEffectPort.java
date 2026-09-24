package mycelium.rule_effects.v1.fixtures;

import mycelium.rule_effects.v1.model.EffectSpec;
import mycelium.rule_effects.v1.model.EntityId;
import mycelium.rule_effects.v1.model.Tx;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Deliberately incorrect: ignores the stable id and mints a fresh id on every apply (the classic
 * random-modifier-id bug). Applying the same rule twice stacks a second modifier.
 */
public final class StackingEffectPort extends MemoryEffectPort {
    private int counter;

    @Override
    public boolean apply(EntityId entity, EffectSpec effect, Tx tx) {
        Set<String> ids = active.computeIfAbsent(entity, k -> new LinkedHashSet<>());
        String minted = effect.id() + "#" + (++counter);
        ids.add(minted);
        tx.registerUndo(() -> ids.remove(minted));
        return true;
    }
}
