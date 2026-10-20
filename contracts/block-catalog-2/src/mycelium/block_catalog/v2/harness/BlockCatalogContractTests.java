package mycelium.block_catalog.v2.harness;

import mycelium.block_catalog.v2.model.BlockCatalog;
import mycelium.harness.Check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** block.catalog@2: laws for finite immutable block-registry snapshots in capture order (SPEC.md). */
public final class BlockCatalogContractTests {
    private static final String VERSION = "26.3";
    private final Supplier<BlockCatalogFactory> factories;

    public BlockCatalogContractTests(Supplier<BlockCatalogFactory> factories) {
        this.factories = factories;
    }

    public Check run() {
        Check c = new Check();
        c1ExactMembership(c);
        c2Defaults(c);
        c3ExplicitProperties(c);
        c4MalformedDefinitions(c);
        c5DeepImmutability(c);
        c6CaptureOrder(c);
        return c;
    }

    /** Membership only: C6 owns domain order, so this law compares each domain as a set. */
    private void c1ExactMembership(Check c) {
        BlockCatalog catalog = create(c, "C1");
        if (catalog == null) { c.that(false, "C1:known-blocks"); return; }
        BlockCatalog.Entry stairs = catalog.blocks().get("minecraft:oak_stairs");
        boolean exact = new ArrayList<>(catalog.blocks().keySet()).equals(List.of("minecraft:oak_stairs", "othermod:custom/block"))
                && stairs != null
                && new ArrayList<>(stairs.properties().keySet()).equals(List.of("facing", "half", "rotation", "waterlogged"))
                && Set.copyOf(stairs.properties().get("facing")).equals(Set.of("north", "south", "east", "west"))
                && rejects(() -> catalog.normalize("minecraft:unknown", Map.of()))
                && rejects(() -> catalog.normalize("othermod:oak_stairs", Map.of()));
        c.that(exact, "C1:known-blocks");
    }

    private void c2Defaults(Check c) {
        BlockCatalog catalog = create(c, "C2");
        if (catalog == null) { c.that(false, "C2:all-defaults"); return; }
        Map<String, String> stairs = normalize(catalog, "minecraft:oak_stairs", Map.of());
        Map<String, String> custom = normalize(catalog, "othermod:custom/block", Map.of());
        c.that(Map.of("facing", "north", "half", "bottom", "rotation", "0", "waterlogged", "false").equals(stairs)
                && Map.of("variant", "blue").equals(custom), "C2:all-defaults");
    }

    private void c3ExplicitProperties(Check c) {
        BlockCatalog catalog = create(c, "C3");
        if (catalog == null) { c.that(false, "C3:explicit-overrides"); return; }
        Map<String, String> state = normalize(catalog, "minecraft:oak_stairs",
                Map.of("facing", "south", "waterlogged", "true", "rotation", "11"));
        c.that(Map.of("facing", "south", "half", "bottom", "rotation", "11", "waterlogged", "true").equals(state)
                && rejects(() -> catalog.normalize("minecraft:oak_stairs", Map.of("unknown", "north")))
                && rejects(() -> catalog.normalize("minecraft:oak_stairs", Map.of("facing", "up")))
                && rejects(() -> catalog.normalize("minecraft:oak_stairs", Map.of("rotation", "16"))), "C3:explicit-overrides");
    }

    private void c4MalformedDefinitions(Check c) {
        BlockCatalogFactory factory = factories.get();
        List<Definition> invalid = List.of(
                new Definition("  ", definitions()),
                new Definition(VERSION, Map.of("Upper:stone", stairs())),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("bad-name", List.of("x")), Map.of("bad-name", "x")))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of()), Map.of("facing", "north")))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of("north", "north")), Map.of("facing", "north")))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of("bad value")), Map.of("facing", "bad value")))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of(" ")), Map.of("facing", " ")))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of("north,east")), Map.of("facing", "north,east")))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of("north")), Map.of()))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of("north")), Map.of("extra", "north")))),
                new Definition(VERSION, Map.of("minecraft:bad", new BlockCatalog.Entry(Map.of("facing", List.of("north")), Map.of("facing", "south")))));
        boolean allRejected = true;
        for (Definition definition : invalid) {
            try {
                factory.create(definition.version(), definition.blocks());
                allRejected = false;
            } catch (RuntimeException expected) {
                // Each malformed form is checked at the constructor seam.
            }
        }
        c.that(allRejected, "C4:rejects-malformed");
    }

    private void c5DeepImmutability(Check c) {
        Map<String, List<String>> domains = new LinkedHashMap<>();
        List<String> facing = new ArrayList<>(List.of("north", "south"));
        domains.put("facing", facing);
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("facing", "north");
        Map<String, BlockCatalog.Entry> input = new LinkedHashMap<>();
        input.put("minecraft:oak_stairs", new BlockCatalog.Entry(domains, defaults));
        BlockCatalog catalog = create(factories.get(), VERSION, input);
        if (catalog == null) { c.that(false, "C5:deep-immutable"); return; }
        domains.put("half", List.of("top"));
        facing.clear();
        defaults.put("facing", "south");
        input.clear();
        BlockCatalog.Entry entry = catalog.blocks().get("minecraft:oak_stairs");
        boolean unchanged = entry != null
                && entry.properties().get("facing").equals(List.of("north", "south"))
                && entry.defaults().get("facing").equals("north");
        boolean immutable = entry != null
                && unmodifiable(() -> catalog.blocks().put("minecraft:stone", entry))
                && unmodifiable(() -> entry.properties().put("half", List.of("top")))
                && unmodifiable(() -> entry.properties().get("facing").add("east"))
                && unmodifiable(() -> entry.defaults().put("facing", "south"))
                && unmodifiable(() -> normalize(catalog, "minecraft:oak_stairs", Map.of()).put("facing", "south"));
        c.that(unchanged && immutable, "C5:deep-immutable");
    }

    /**
     * Every domain keeps the capture's own order. Both shapes a sort would destroy are checked: a
     * direction enum whose declaration order is not alphabetical, and numeric values whose text
     * order is not their numeric order.
     */
    private void c6CaptureOrder(Check c) {
        BlockCatalog catalog = create(factories.get(), VERSION, definitions());
        if (catalog == null) { c.that(false, "C6:capture-order"); return; }
        BlockCatalog.Entry stairs = catalog.blocks().get("minecraft:oak_stairs");
        BlockCatalog.Entry custom = catalog.blocks().get("othermod:custom/block");
        boolean preserved = stairs != null && custom != null
                && stairs.properties().get("facing").equals(List.of("north", "south", "east", "west"))
                && stairs.properties().get("rotation").equals(ROTATION)
                && stairs.properties().get("waterlogged").equals(List.of("false", "true"))
                && custom.properties().get("variant").equals(List.of("blue", "amber"));
        c.that(preserved, "C6:capture-order");
    }

    private BlockCatalog create(Check c, String law) {
        BlockCatalog catalog = create(factories.get(), VERSION, definitions());
        if (catalog == null) {
            c.that(false, switch (law) {
                case "C1" -> "C1:known-blocks";
                case "C2" -> "C2:all-defaults";
                case "C3" -> "C3:explicit-overrides";
                default -> throw new IllegalArgumentException("unknown contract law " + law);
            });
        }
        return catalog;
    }

    private static BlockCatalog create(BlockCatalogFactory factory, String version, Map<String, BlockCatalog.Entry> blocks) {
        try {
            return factory.create(version, blocks);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Map<String, String> normalize(BlockCatalog catalog, String id, Map<String, String> explicit) {
        try {
            return catalog.normalize(id, explicit);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean rejects(Runnable body) {
        try {
            body.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static boolean unmodifiable(Runnable mutation) {
        try {
            mutation.run();
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }

    /** 0..15 in numeric order: an alphabetical sort would put "10" directly after "1". */
    static final List<String> ROTATION =
            List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");

    static Map<String, BlockCatalog.Entry> definitions() {
        Map<String, BlockCatalog.Entry> blocks = new LinkedHashMap<>();
        blocks.put("othermod:custom/block", new BlockCatalog.Entry(
                domain("variant", List.of("blue", "amber")), Map.of("variant", "blue")));
        blocks.put("minecraft:oak_stairs", stairs());
        return blocks;
    }

    private static BlockCatalog.Entry stairs() {
        Map<String, List<String>> properties = new LinkedHashMap<>();
        properties.put("facing", List.of("north", "south", "east", "west"));
        properties.put("half", List.of("bottom", "top"));
        properties.put("waterlogged", List.of("false", "true"));
        properties.put("rotation", ROTATION);
        return new BlockCatalog.Entry(properties,
                Map.of("facing", "north", "half", "bottom", "waterlogged", "false", "rotation", "0"));
    }

    private static Map<String, List<String>> domain(String name, List<String> values) {
        Map<String, List<String>> properties = new LinkedHashMap<>();
        properties.put(name, values);
        return properties;
    }

    private record Definition(String version, Map<String, BlockCatalog.Entry> blocks) {
    }
}
