package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.DimensionId;
import mycelium.dimension_travel.v2.model.Inventory;
import mycelium.dimension_travel.v2.model.ItemToken;
import mycelium.dimension_travel.v2.model.Journal;
import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.PortFailure;
import mycelium.dimension_travel.v2.model.Pose;
import mycelium.dimension_travel.v2.model.Position;
import mycelium.dimension_travel.v2.model.Trait;
import mycelium.dimension_travel.v2.model.TravelPort;
import mycelium.dimension_travel.v2.model.TravelTx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reference in-memory port with a durable {@link Store}: {@link #restart()} is a new process over the same store,
 * which is how the harness simulates a crash (no undo runs, nothing in memory survives, the store does).
 */
public class MemoryTravelPort implements TravelPort {
    private static final Position ABSENT = new Position(new DimensionId("mycelium:unknown"), 0, 0, 0, 0f, 0f);

    /** What survives the process: positions, inventories, the trip journal. */
    public static final class Store {
        final Map<PlayerId, Position> positions = new HashMap<>();
        final Map<PlayerId, Inventory> inventories = new HashMap<>();
        final Map<PlayerId, Journal> journals = new HashMap<>();
    }

    protected final Store store;
    /** Test hooks: the next move throws (a crash mid-move), or does nothing (the target never loaded). */
    public boolean failNextMove;
    public boolean ignoreNextMove;
    /** Whether a journal was already written when {@code move} ran (L1). */
    public Boolean journalSeenAtMove;

    public MemoryTravelPort() {
        this(new Store());
    }

    protected MemoryTravelPort(Store store) {
        this.store = store;
    }

    /** A new process over the same durable store. Subclasses return their own kind. */
    public MemoryTravelPort restart() {
        return new MemoryTravelPort(store);
    }

    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.POSITION_EXACT, Trait.INVENTORY_PRESERVING, Trait.JOURNALED);
    }

    @Override
    public Position where(PlayerId player) {
        return store.positions.getOrDefault(player, ABSENT);
    }

    @Override
    public Inventory inventory(PlayerId player) {
        return store.inventories.getOrDefault(player, new Inventory(List.of()));
    }

    @Override
    public boolean exists(PlayerId player, DimensionId dimension) {
        Position p = store.positions.get(player);
        return p != null && p.dimension().equals(dimension);
    }

    @Override
    public void move(PlayerId player, Position target, Pose pose, TravelTx tx) {
        journalSeenAtMove = journal(player).isPresent();
        if (failNextMove) {
            failNextMove = false;
            store.positions.put(player, new Position(target.dimension(), target.x(), target.y() + 0.5, target.z(), 0f, 0f));
            throw new PortFailure("the process died mid-move");
        }
        if (ignoreNextMove) {
            ignoreNextMove = false;
            return;
        }
        Position previous = store.positions.get(player);
        store.positions.put(player, target);
        tx.registerUndo(() -> {
            if (previous == null) {
                store.positions.remove(player);
            } else {
                store.positions.put(player, previous);
            }
        });
    }

    @Override
    public Optional<Journal> journal(PlayerId player) {
        return Optional.ofNullable(store.journals.get(player));
    }

    @Override
    public void writeJournal(PlayerId player, Journal journal, TravelTx tx) {
        Journal previous = store.journals.put(player, journal);
        tx.registerUndo(() -> restore(player, previous));
    }

    @Override
    public void strikeJournal(PlayerId player, TravelTx tx) {
        Journal previous = store.journals.remove(player);
        tx.registerUndo(() -> restore(player, previous));
    }

    private void restore(PlayerId player, Journal previous) {
        if (previous == null) {
            store.journals.remove(player);
        } else {
            store.journals.put(player, previous);
        }
    }

    /** Fixture setup outside any transaction. */
    public MemoryTravelPort seed(PlayerId player, Position position, Inventory inventory) {
        store.positions.put(player, position);
        store.inventories.put(player, inventory);
        return this;
    }

    /** The traveller's inventory changes while away (what they found there). */
    public void give(PlayerId player, Inventory inventory) {
        store.inventories.put(player, inventory);
    }

    public static ItemToken item(String id, int count) {
        return new ItemToken(id, count);
    }
}
