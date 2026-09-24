package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.DimensionId;
import mycelium.dimension_travel.v1.model.Inventory;
import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.Trait;
import mycelium.dimension_travel.v1.model.TravelPort;
import mycelium.dimension_travel.v1.model.TravelTx;

import java.util.Set;

/** Base for deliberately incorrect ports: forwards everything to a {@link MemoryTravelPort}. */
abstract class ForwardingTravelPort implements TravelPort, HasMemory {
    protected final MemoryTravelPort delegate = new MemoryTravelPort();

    @Override
    public Set<Trait> traits() {
        return delegate.traits();
    }

    @Override
    public Position where(PlayerId player) {
        return delegate.where(player);
    }

    @Override
    public Inventory inventory(PlayerId player) {
        return delegate.inventory(player);
    }

    @Override
    public boolean exists(PlayerId player, DimensionId dimension) {
        return delegate.exists(player, dimension);
    }

    @Override
    public void move(PlayerId player, Position target, Pose pose, TravelTx tx) {
        delegate.move(player, target, pose, tx);
    }

    @Override
    public MemoryTravelPort memory() {
        return delegate;
    }
}
