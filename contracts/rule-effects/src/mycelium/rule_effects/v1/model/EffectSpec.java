package mycelium.rule_effects.v1.model;

/**
 * One effect a rule applies to an entity. Every spec has a stable {@link #id()}: the key under which the
 * provider tracks it. Stable ids are what make apply idempotent and remove exact.
 */
public sealed interface EffectSpec {
    String id();

    record Attribute(String attribute, Op op, double value, String modifierId) implements EffectSpec {
        public Attribute {
            if (attribute == null || attribute.isBlank()) {
                throw new IllegalArgumentException("attribute must be non-blank");
            }
            if (op == null) {
                throw new IllegalArgumentException("op");
            }
            if (modifierId == null || modifierId.isBlank()) {
                throw new IllegalArgumentException("modifier id must be non-blank");
            }
        }

        @Override
        public String id() {
            return modifierId;
        }
    }

    record Flag(String flag) implements EffectSpec {
        public Flag {
            if (flag == null || flag.isBlank()) {
                throw new IllegalArgumentException("flag must be non-blank");
            }
        }

        @Override
        public String id() {
            return flag;
        }
    }

    record StatusEffect(String effect, int amplifier) implements EffectSpec {
        public StatusEffect {
            if (effect == null || effect.isBlank()) {
                throw new IllegalArgumentException("effect must be non-blank");
            }
            if (amplifier < 0) {
                throw new IllegalArgumentException("amplifier must be >= 0");
            }
        }

        @Override
        public String id() {
            return effect;
        }
    }
}
