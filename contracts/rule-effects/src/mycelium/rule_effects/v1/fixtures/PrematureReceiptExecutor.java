package mycelium.rule_effects.v1.fixtures;

import mycelium.rule_effects.v1.model.EffectFeedback;
import mycelium.rule_effects.v1.model.EffectOutcome;
import mycelium.rule_effects.v1.model.EffectPort;
import mycelium.rule_effects.v1.model.EffectSpec;
import mycelium.rule_effects.v1.model.EntityId;
import mycelium.rule_effects.v1.model.Reason;
import mycelium.rule_effects.v1.model.Receipt;
import mycelium.rule_effects.v1.model.Tx;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deliberately incorrect: hands the receipt to feedback at execution time instead of at the outermost commit. */
public final class PrematureReceiptExecutor {
    private PrematureReceiptExecutor() {
    }

    public static EffectOutcome apply(EffectPort port, Set<EntityId> entities, String ruleId,
                                      List<EffectSpec> effects, Tx tx, EffectFeedback feedback) {
        if (entities.isEmpty()) {
            return new EffectOutcome.Rejected(Reason.EMPTY_SCOPE);
        }
        if (effects.isEmpty()) {
            return new EffectOutcome.Rejected(Reason.EMPTY_EFFECTS);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (EffectSpec effect : effects) {
            ids.add(effect.id());
        }
        for (EntityId entity : entities) {
            for (EffectSpec effect : effects) {
                if (port.active(entity).contains(effect.id())) {
                    continue;
                }
                if (!port.apply(entity, effect, tx)) {
                    return new EffectOutcome.Rejected(Reason.APPLY_FAILED);
                }
            }
        }
        Receipt.ApplyReceipt receipt = new Receipt.ApplyReceipt(ruleId, entities, ids);
        feedback.applied(receipt);
        return new EffectOutcome.Committed(receipt);
    }
}
