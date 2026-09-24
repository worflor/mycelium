package mycelium.rule_effects.v1.model;

/** A disagreement between the ledger and the port found by {@link EffectLedger#reconcile}. */
public record Leak(EntityId entity, String id, Kind kind) {
    public enum Kind {
        /** Active on the port but no rule in the ledger owns it: an apply without a matching remove. */
        ORPHAN_ON_PORT,
        /** Owned by a rule in the ledger but not active on the port: a remove the ledger never saw. */
        MISSING_ON_PORT
    }
}
