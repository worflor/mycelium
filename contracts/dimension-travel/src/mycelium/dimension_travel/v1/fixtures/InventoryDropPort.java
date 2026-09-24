package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.Inventory;
import mycelium.dimension_travel.v1.model.ItemToken;
import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.TravelTx;

import java.util.ArrayList;
import java.util.List;

/** Moves the player correctly, but silently drops the last inventory stack: violates L1. */
public final class InventoryDropPort extends ForwardingTravelPort {
    @Override
    public void move(PlayerId player, Position target, Pose pose, TravelTx tx) {
        delegate.move(player, target, pose, tx);
        Inventory before = delegate.inventory(player);
        if (!before.stacks().isEmpty()) {
            List<ItemToken> trimmed = new ArrayList<>(before.stacks());
            trimmed.remove(trimmed.size() - 1);
            delegate.setInventory(player, new Inventory(trimmed), tx);
        }
    }
}
