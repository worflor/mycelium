package mycelium.bridges.block_catalog.conformance;

import mycelium.block_catalog.v2.model.BlockCatalog;
import mycelium.block_catalog.v2.model.CatalogSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native registry conformance through the model port, usable by consumer overrides too. */
public final class CatalogCases {
    private CatalogCases() { }

    public record Case(String name, Runnable body) {
        public void run() { body.run(); }
    }

    public static List<Case> cases(CatalogSource source) {
        BlockCatalog actual = source.capture();
        Map<String, BlockCatalog.Entry> expected = CatalogWire.entries();
        return List.of(
                new Case("native_membership", () -> expect(actual.version().equals(CatalogWire.version())
                        && actual.blocks().keySet().equals(expected.keySet()), "version and every registered namespace")),
                new Case("native_domains", () -> {
                    for (var entry : expected.entrySet()) {
                        var found = actual.blocks().get(entry.getKey());
                        expect(found != null && found.properties().keySet().equals(entry.getValue().properties().keySet()),
                                "property keys for " + entry.getKey());
                        // block.catalog@2 C6: the capture's order, so compare the lists, not their contents.
                        for (var property : entry.getValue().properties().entrySet())
                            expect(property.getValue().equals(found.properties().get(property.getKey())),
                                    "property domain order for " + entry.getKey() + "/" + property.getKey());
                    }
                }),
                // Guards the case above against being vacuous: if every real domain happened to be
                // in alphabetical order, a sorting capture would pass it and prove nothing.
                new Case("native_order_is_not_alphabetical", () -> {
                    List<String> unsorted = new ArrayList<>();
                    for (var entry : expected.entrySet())
                        for (var property : entry.getValue().properties().entrySet()) {
                            List<String> sorted = new ArrayList<>(property.getValue());
                            sorted.sort(null);
                            if (!sorted.equals(property.getValue()))
                                unsorted.add(entry.getKey() + "/" + property.getKey());
                        }
                    expect(!unsorted.isEmpty(), "the game declares at least one domain out of alphabetical order");
                    for (String path : unsorted) {
                        String id = path.substring(0, path.lastIndexOf('/'));
                        String property = path.substring(path.lastIndexOf('/') + 1);
                        expect(expected.get(id).properties().get(property)
                                        .equals(actual.blocks().get(id).properties().get(property)),
                                "unsorted native domain preserved for " + path);
                    }
                }),
                new Case("native_defaults", () -> {
                    for (var entry : expected.entrySet())
                        expect(actual.normalize(entry.getKey(), Map.of()).equals(entry.getValue().defaults()),
                                "default state for " + entry.getKey());
                }));
    }

    private static void expect(boolean value, String detail) {
        if (!value) throw new AssertionError("block catalog conformance: " + detail);
    }
}
