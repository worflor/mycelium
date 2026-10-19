package mycelium.bridges.block_catalog.conformance;

import mycelium.block_catalog.v1.model.BlockCatalog;
import mycelium.block_catalog.v1.model.CatalogSource;

import java.util.HashSet;
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
                        for (var property : entry.getValue().properties().entrySet())
                            expect(new HashSet<>(property.getValue()).equals(new HashSet<>(found.properties().get(property.getKey()))),
                                    "property domain for " + entry.getKey() + "/" + property.getKey());
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
