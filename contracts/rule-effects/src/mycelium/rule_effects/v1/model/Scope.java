package mycelium.rule_effects.v1.model;

/** The locale a rule application is scoped to. Entities outside the scope's entity set are never touched. */
public record Scope(String localeId) {
    public Scope {
        if (localeId == null || localeId.isBlank()) {
            throw new IllegalArgumentException("locale id must be non-blank");
        }
    }
}
