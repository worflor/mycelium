package mycelium.storage_access.v1.fixtures;

import mycelium.storage_access.v1.model.FluidUnit;
import mycelium.storage_access.v1.model.Goods;

/**
 * The fixtures' goods and units. The units are the rows of {@code eras/loaders.json} ({@code fluid.perBucket}), written here
 * as fixture values: Fabric's droplets and NeoForge's and Forge's millibuckets.
 */
public final class Catalog {
    private Catalog() {
    }

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    public static final Goods IRON = Goods.item("minecraft:iron_ingot");
    public static final Goods GOLD = Goods.item("minecraft:gold_ingot");
    public static final Goods COAL = Goods.item("minecraft:coal");
    public static final Goods BUCKET = Goods.item("minecraft:bucket");
    public static final Goods PEARL = Goods.item("minecraft:ender_pearl");
    public static final Goods NAMED_IRON = Goods.item("minecraft:iron_ingot{custom_name:'Ownership'}");
    public static final Goods WATER = Goods.fluid("minecraft:water");
    public static final Goods LAVA = Goods.fluid("minecraft:lava");

    /** Fabric's transfer API: 81,000 droplets to a bucket. */
    public static final FluidUnit DROPLETS = new FluidUnit(81_000);
    /** NeoForge and Forge: 1,000 millibuckets to a bucket. */
    public static final FluidUnit MILLIBUCKETS = new FluidUnit(1_000);

    /** The goods' own stack size. */
    public static long maxStack(Goods goods) {
        if (goods.equals(PEARL) || goods.equals(BUCKET)) {
            return 16;
        }
        return 64;
    }

    public static boolean fuel(Goods goods) {
        return goods.equals(COAL);
    }
}
