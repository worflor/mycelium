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

/**
 * {@code move} does not take effect immediately: it is only applied by a later {@link #tick()} call, as if
 * the target chunk were still loading. Used to exercise the "move did not throw, but arrival cannot yet be
 * confirmed" path (Rejected(TARGET_UNLOADED) for a correct executor).
 */
public final class LaggyPort implements TravelPort, HasMemory {
    private final MemoryTravelPort delegate = new MemoryTravelPort();
    private PlayerId pendingPlayer;
    private Position pendingTarget;
    private Pose pendingPose;

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
        this.pendingPlayer = player;
        this.pendingTarget = target;
        this.pendingPose = pose;
    }

    /**
     * Applies the delayed move, as if the target chunk finished loading this tick. The engine tick is not
     * scoped to the request's own transaction (which may already be aborted or committed by the time the
     * chunk finishes loading), so this applies the move under a fresh transaction of its own.
     */
    public void tick() {
        if (pendingPlayer != null) {
            TravelTx engineTick = TravelTx.open();
            delegate.move(pendingPlayer, pendingTarget, pendingPose, engineTick);
            engineTick.commit();
            pendingPlayer = null;
        }
    }

    @Override
    public MemoryTravelPort memory() {
        return delegate;
    }

    public LaggyPort seed(PlayerId player, Position position, Inventory inventory) {
        delegate.seed(player, position, inventory);
        return this;
    }
}
