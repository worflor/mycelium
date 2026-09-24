package mycelium.rule_effects.v1.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reference executor for {@code rule.effects@1}. */
public final class RuleEffects {
    private RuleEffects() {
    }

    /**
     * Applies every effect of a rule to every entity in scope. Ids already active on an entity are not
     * re-applied (stable ids, no stacking). The receipt is handed to feedback only at the outermost commit.
     */
    public static EffectOutcome apply(EffectPort port, Set<EntityId> entities, String ruleId,
                                      List<EffectSpec> effects, Tx tx, EffectFeedback feedback) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(feedback, "feedback");

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
        tx.onOuterCommit(() -> feedback.applied(receipt));
        return new EffectOutcome.Committed(receipt);
    }

    /**
     * Removes every id the ledger attributes to the rule on every entity in scope. Ids the port no longer
     * reports as active are skipped. If nothing was owned, the operation is rejected: zero effect is not a receipt.
     */
    public static EffectOutcome removeRule(EffectPort port, Set<EntityId> entities, String ruleId,
                                           EffectLedger ledger, Tx tx, EffectFeedback feedback) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(feedback, "feedback");

        if (entities.isEmpty()) {
            return new EffectOutcome.Rejected(Reason.EMPTY_SCOPE);
        }

        Set<String> removed = new LinkedHashSet<>();
        Set<EntityId> touched = new LinkedHashSet<>();
        for (EntityId entity : entities) {
            Set<String> owned = ledger.idsFor(entity, ruleId);
            if (owned.isEmpty()) {
                continue;
            }
            for (String id : owned) {
                if (port.active(entity).contains(id) && !port.remove(entity, id, tx)) {
                    return new EffectOutcome.Rejected(Reason.REMOVE_FAILED);
                }
            }
            removed.addAll(owned);
            touched.add(entity);
        }
        if (touched.isEmpty()) {
            return new EffectOutcome.Rejected(Reason.NOTHING_TO_REMOVE);
        }

        Receipt.RemoveReceipt receipt = new Receipt.RemoveReceipt(ruleId, touched, removed);
        tx.onOuterCommit(() -> feedback.removed(receipt));
        return new EffectOutcome.Committed(receipt);
    }
}
