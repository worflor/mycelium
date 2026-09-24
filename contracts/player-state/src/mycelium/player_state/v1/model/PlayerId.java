package mycelium.player_state.v1.model;

public record PlayerId(String token) {
    public PlayerId {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("player token must be non-blank");
        }
    }
}
