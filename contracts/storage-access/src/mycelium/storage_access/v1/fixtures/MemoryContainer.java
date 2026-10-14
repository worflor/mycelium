package mycelium.storage_access.v1.fixtures;

import mycelium.storage_access.v1.model.Face;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Holding;
import mycelium.storage_access.v1.model.SlottedContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A vanilla-shaped container in memory: a chest (unsided), a furnace (input on top, fuel from the sides, output and empty
 * buckets from below) or an offering plate (slot 0 takes from any face and gives nothing; slots 1 to 9 give to any face and
 * take nothing). Like the game's own container object, it does not check whether it still stands: its handles must.
 */
public final class MemoryContainer implements SlottedContainer, Block {
    private enum Shape { CHEST, FURNACE, PLATE }

    private final Shape shape;
    private final List<Optional<Holding>> slots = new ArrayList<>();
    private boolean present = true;
    private boolean vanishOnWrite;
    private int writes;

    private MemoryContainer(Shape shape, int size) {
        this.shape = shape;
        for (int i = 0; i < size; i++) {
            slots.add(Optional.empty());
        }
    }

    public static MemoryContainer chest(int size) {
        return new MemoryContainer(Shape.CHEST, size);
    }

    public static MemoryContainer furnace() {
        return new MemoryContainer(Shape.FURNACE, 3);
    }

    public static MemoryContainer plate() {
        return new MemoryContainer(Shape.PLATE, 10);
    }

    /** Seeds a slot outside any transaction (setting up a fixture). */
    public MemoryContainer with(int slot, Goods goods, long amount) {
        slots.set(slot, amount > 0 ? Optional.of(new Holding(goods, amount)) : Optional.empty());
        return this;
    }

    /** The container's own stack limit, before the goods' own. */
    public long stackLimit() {
        return 64;
    }

    public boolean isPlate() {
        return shape == Shape.PLATE;
    }

    public long count(Goods goods) {
        long n = 0;
        for (Optional<Holding> h : slots) {
            if (h.isPresent() && h.get().goods().equals(goods)) {
                n += h.get().amount();
            }
        }
        return n;
    }

    public long countAt(int slot) {
        return slots.get(slot).map(Holding::amount).orElse(0L);
    }

    public int writes() {
        return writes;
    }

    @Override
    public boolean present() {
        return present;
    }

    @Override
    public void vanish() {
        present = false;
    }

    @Override
    public void vanishOnNextWrite() {
        vanishOnWrite = true;
    }

    @Override
    public void disarm() {
        vanishOnWrite = false;
    }

    @Override
    public Object snapshot() {
        return List.copyOf(slots);
    }

    @Override
    public int size() {
        return slots.size();
    }

    @Override
    public Optional<Holding> at(int slot) {
        return slots.get(slot);
    }

    @Override
    public long limit(int slot, Goods goods) {
        return Math.min(stackLimit(), Catalog.maxStack(goods));
    }

    @Override
    public int[] slotsFor(Face face) {
        if (shape == Shape.FURNACE) {
            return switch (face) {
                case UP -> new int[] {0};
                case DOWN -> new int[] {2, 1};
                default -> new int[] {1};
            };
        }
        int[] all = new int[slots.size()];
        Arrays.setAll(all, i -> i);
        return all;
    }

    @Override
    public boolean canPlace(int slot, Goods goods, Face face) {
        return switch (shape) {
            case CHEST -> true;
            case FURNACE -> slot == 0 || (slot == 1 && Catalog.fuel(goods));
            case PLATE -> slot == 0 && slots.get(0).map(h -> h.goods().equals(goods)).orElse(true);
        };
    }

    @Override
    public boolean canTake(int slot, Goods goods, Face face) {
        return switch (shape) {
            case CHEST -> true;
            case FURNACE -> !(face == Face.DOWN && slot == 1) || goods.equals(Catalog.BUCKET);
            case PLATE -> slot != 0;
        };
    }

    @Override
    public void put(int slot, Optional<Holding> holding) {
        slots.set(slot, holding);
        writes++;
        if (vanishOnWrite) {
            vanishOnWrite = false;
            present = false;
        }
    }

    @Override
    public String toString() {
        return shape + slots.toString();
    }
}
