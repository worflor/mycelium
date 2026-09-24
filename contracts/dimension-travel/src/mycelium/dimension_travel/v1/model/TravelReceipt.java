package mycelium.dimension_travel.v1.model;

import java.util.Objects;

public record TravelReceipt(PlayerId player, Position from, Position to, Pose pose) {
    public TravelReceipt {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(pose, "pose");
    }
}
