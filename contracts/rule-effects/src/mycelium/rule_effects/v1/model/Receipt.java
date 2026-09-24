package mycelium.rule_effects.v1.model;

import java.util.Set;

/** What actually committed. A receipt with no entities or no ids is not a receipt. */
public sealed interface Receipt {
    String ruleId();

    Set<EntityId> entities();

    Set<String> ids();

    record ApplyReceipt(String ruleId, Set<EntityId> entities, Set<String> ids) implements Receipt {
        public ApplyReceipt {
            Receipt.validate(ruleId, entities, ids);
            entities = Set.copyOf(entities);
            ids = Set.copyOf(ids);
        }
    }

    record RemoveReceipt(String ruleId, Set<EntityId> entities, Set<String> ids) implements Receipt {
        public RemoveReceipt {
            Receipt.validate(ruleId, entities, ids);
            entities = Set.copyOf(entities);
            ids = Set.copyOf(ids);
        }
    }

    static void validate(String ruleId, Set<EntityId> entities, Set<String> ids) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("rule id must be non-blank");
        }
        if (entities == null || entities.isEmpty()) {
            throw new IllegalArgumentException("a receipt names at least one entity");
        }
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("a receipt names at least one id; zero effect is a rejection");
        }
    }
}
