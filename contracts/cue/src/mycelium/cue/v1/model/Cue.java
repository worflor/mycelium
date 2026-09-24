package mycelium.cue.v1.model;

public record Cue(String kind, String profile, String key) {
    public Cue {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must be non-blank");
        }
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile must be non-blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must be non-blank");
        }
    }
}
