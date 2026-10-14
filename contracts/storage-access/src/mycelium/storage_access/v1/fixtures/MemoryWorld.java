package mycelium.storage_access.v1.fixtures;

import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.Position;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Levels of positions, each holding a block that keeps goods. A block removed vanishes and is kept aside, holding what it
 * held: what the game does with a removed block's goods is the platform's, and the fixtures count them where they lie, so
 * goods moved into or out of a vanished block show.
 */
public final class MemoryWorld {
    private final Map<String, Map<Position, Block>> levels = new LinkedHashMap<>();
    private final List<Block> removed = new ArrayList<>();

    public <B extends Block> B place(String level, Position position, B block) {
        Block old = levels.computeIfAbsent(level, k -> new LinkedHashMap<>()).put(position, block);
        if (old != null) {
            old.vanish();
            removed.add(old);
        }
        return block;
    }

    public Block at(String level, Position position) {
        Map<Position, Block> blocks = levels.get(level);
        return blocks == null ? null : blocks.get(position);
    }

    /** The first level, in placing order, with a block at the position (what a level-blind lookup finds). */
    public Block anywhereAt(Position position) {
        for (Map<Position, Block> blocks : levels.values()) {
            if (blocks.containsKey(position)) {
                return blocks.get(position);
            }
        }
        return null;
    }

    public void remove(String level, Position position) {
        Map<Position, Block> blocks = levels.get(level);
        Block old = blocks == null ? null : blocks.remove(position);
        if (old != null) {
            old.vanish();
            removed.add(old);
        }
    }

    public List<Block> blocks() {
        List<Block> out = new ArrayList<>();
        levels.values().forEach(m -> out.addAll(m.values()));
        out.addAll(removed);
        return out;
    }

    /** Every block's contents, live and removed, as one value: equal exactly when nothing anywhere changed. */
    public Object snapshot() {
        List<Object> out = new ArrayList<>();
        for (Block b : blocks()) {
            out.add(b.snapshot());
        }
        return out;
    }

    /**
     * The world's total of {@code goods}, exactly: items counted; fluids in {@link #FINE} parts of a bucket, so droplets and
     * millibuckets add without rounding.
     */
    public BigInteger total(Goods goods) {
        BigInteger sum = BigInteger.ZERO;
        if (goods.kind() == Kind.ITEM) {
            for (Block b : blocks()) {
                if (b instanceof MemoryContainer c) {
                    sum = sum.add(BigInteger.valueOf(c.count(goods)));
                }
            }
            return sum;
        }
        for (Block b : blocks()) {
            if (b instanceof MemoryTank t) {
                if (FINE % t.unit().perBucket() != 0) {
                    throw new IllegalStateException("a fixture tank's unit must divide " + FINE + ": " + t.unit());
                }
                sum = sum.add(BigInteger.valueOf(t.amountOf(goods)).multiply(BigInteger.valueOf(FINE / t.unit().perBucket())));
            }
        }
        return sum;
    }

    /** The fixtures' common measure of fluid: parts of a bucket every fixture unit divides (droplets and millibuckets). */
    public static final long FINE = 81_000L * 1_000L;
}
