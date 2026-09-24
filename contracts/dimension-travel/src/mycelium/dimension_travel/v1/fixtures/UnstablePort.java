package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.PortFailure;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.TravelTx;

/** Partially acts (registers the move) and then always throws PortFailure: exercises Indeterminate. */
public final class UnstablePort extends ForwardingTravelPort {
    @Override
    public void move(PlayerId player, Position target, Pose pose, TravelTx tx) {
        delegate.move(player, target, pose, tx);
        throw new PortFailure("target dimension timed out mid-move");
    }
}
