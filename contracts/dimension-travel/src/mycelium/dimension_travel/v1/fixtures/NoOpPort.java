package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.TravelTx;

/** Declares the traits, honest inspection, never actually moves the player. Must be caught by P1. */
public final class NoOpPort extends ForwardingTravelPort {
    @Override
    public void move(PlayerId player, Position target, Pose pose, TravelTx tx) {
        // intentionally does nothing
    }
}
