package mycelium.cue.v1.model;

public record ProfileId(String token) {
    public ProfileId {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("profile token must be non-blank");
        }
    }
}
