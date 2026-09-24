package mycelium.world_copy.v1.model;

/** Opaque identity of a block state as the provider knows it. Equality is identity of the token text. */
public record BlockToken(String token) {
    public BlockToken {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("block token must be non-blank");
        }
    }
}
