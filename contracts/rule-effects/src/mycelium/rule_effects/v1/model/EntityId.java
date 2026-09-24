package mycelium.rule_effects.v1.model;

/** Opaque identity token of an entity. Equality is identity. */
public record EntityId(String token) {
    public EntityId {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("entity token must be non-blank");
        }
    }
}
