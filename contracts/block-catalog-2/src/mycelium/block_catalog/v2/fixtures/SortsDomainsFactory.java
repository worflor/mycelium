package mycelium.block_catalog.v2.fixtures;

import mycelium.block_catalog.v2.harness.BlockCatalogFactory;
import mycelium.block_catalog.v2.model.BlockCatalog;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Wrongly sorts every property domain alphabetically, which is what @1 promised and what a naive
 * capture does. It stays immutable and deep-copied, so only the capture-order law catches it.
 */
public final class SortsDomainsFactory implements BlockCatalogFactory {
    @Override
    public BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks) {
        BlockCatalog ordered = new BlockCatalog(version, blocks);
        return new BlockCatalog(version, blocks) {
            @Override
            public Map<String, Entry> blocks() {
                Map<String, Entry> sorted = new TreeMap<>();
                ordered.blocks().forEach((id, entry) -> {
                    Map<String, List<String>> properties = new TreeMap<>();
                    entry.properties().forEach((name, values) ->
                            properties.put(name, List.copyOf(new TreeSet<>(values))));
                    sorted.put(id, new Entry(Collections.unmodifiableMap(properties), entry.defaults()));
                });
                return Collections.unmodifiableMap(sorted);
            }
        };
    }
}
