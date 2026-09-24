package mycelium.player_state.v1.model;

public record StateReceipt(PlayerId player, int schema) {
    public StateReceipt {
        if (player == null) {
            throw new IllegalArgumentException("player");
        }
    }
}
