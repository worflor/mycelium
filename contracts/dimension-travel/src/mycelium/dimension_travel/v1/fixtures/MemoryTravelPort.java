package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.DimensionId;
import mycelium.dimension_travel.v1.model.Inventory;
import mycelium.dimension_travel.v1.model.ItemToken;
import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.Trait;
import mycelium.dimension_travel.v1.model.TravelPort;
import mycelium.dimension_travel.v1.model.TravelTx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reference in-memory port. Every mutation registers an undo. An unseeded player reads as absent. */
public class MemoryTravelPort implements TravelPort {
    private static final DimensionId UNKNOWN = new DimensionId("mycelium:unknown");
    private static final Position ABSENT = new Position(UNKNOWN, 0, 0, 0, 0f, 0f);

    private final Map<PlayerId, Position> positions = new HashMap<>();
    private final Map<PlayerId, Inventory> inventories = new HashMap<>();

    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.POSITION_EXACT, Trait.INVENTORY_PRESERVING);
    }

    @Override
    public Position where(PlayerId player) {
        return positions.getOrDefault(player, ABSENT);
    }

    @Override
    public Inventory inventory(PlayerId player) {
        return inventories.getOrDefault(player, new Inventory(List.of()));
    }

    @Override
    public boolean exists(PlayerId player, DimensionId dimension) {
        Position p = positions.get(player);
        return p != null && p.dimension().equals(dimension);
    }

    @Override
    public void move(PlayerId player, Position target, Pose pose, TravelTx tx) {
        Position previous = positions.get(player);
        positions.put(player, target);
        tx.registerUndo(() -> {
            if (previous == null) {
                positions.remove(player);
            } else {
                positions.put(player, previous);
            }
        });
    }

    /** Fixture setup outside any transaction. */
    public MemoryTravelPort seed(PlayerId player, Position position, Inventory inventory) {
        positions.put(player, position);
        inventories.put(player, inventory);
        return this;
    }

    /** Fixture-only mutator used by broken-port fixtures that tamper with inventory on move. */
    public void setInventory(PlayerId player, Inventory inventory, TravelTx tx) {
        Inventory previous = inventories.get(player);
        inventories.put(player, inventory);
        tx.registerUndo(() -> {
            if (previous == null) {
                inventories.remove(player);
            } else {
                inventories.put(player, previous);
            }
        });
    }

    public static ItemToken item(String id, int count) {
        return new ItemToken(id, count);
    }
}
