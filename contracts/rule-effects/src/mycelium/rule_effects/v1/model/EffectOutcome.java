package mycelium.rule_effects.v1.model;

public sealed interface EffectOutcome {
    record Committed(Receipt receipt) implements EffectOutcome {
        public Committed {
            if (receipt == null) {
                throw new IllegalArgumentException("receipt");
            }
        }
    }

    record Rejected(Reason reason) implements EffectOutcome {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    record Unsupported(String detail) implements EffectOutcome {
    }
}
