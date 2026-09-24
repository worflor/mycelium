package mycelium.rule_effects.v1.model;

import java.util.List;
import java.util.Set;

@FunctionalInterface
public interface EffectExecutor {
    EffectOutcome apply(EffectPort port, Set<EntityId> entities, String ruleId, List<EffectSpec> effects,
                        Tx tx, EffectFeedback feedback);
}
