package mycelium.flow.v1.model;

public record ResourceKey(String token) {
    public ResourceKey {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("resource token must be non-blank");
        }
    }
}
