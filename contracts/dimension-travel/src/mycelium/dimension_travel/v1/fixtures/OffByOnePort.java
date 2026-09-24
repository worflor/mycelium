package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.TravelTx;

/** Always lands the player one block off in x from the requested target: violates L2 (position exact). */
public final class OffByOnePort extends ForwardingTravelPort {
    @Override
    public void move(PlayerId player, Position target, Pose pose, TravelTx tx) {
        Position shifted = new Position(target.dimension(), target.x() + 1, target.y(), target.z(),
                target.yaw(), target.pitch());
        delegate.move(player, shifted, pose, tx);
    }
}
