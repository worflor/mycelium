package mycelium.block_catalog.v2.fixtures;

import mycelium.block_catalog.v2.harness.BlockCatalogFactory;
import mycelium.block_catalog.v2.model.BlockCatalog;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Wrongly keeps and exposes the caller's top-level block map; its domain order is faithful. */
public final class AliasesInputsFactory implements BlockCatalogFactory {
    @Override
    public BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks) {
        return new BlockCatalog(version, blocks) {
            @Override
            public Map<String, Entry> blocks() {
                Map<String, Entry> view = new TreeMap<>();
                for (Map.Entry<String, Entry> block : blocks.entrySet()) {
                    Map<String, List<String>> properties = new TreeMap<>();
                    block.getValue().properties().forEach((name, values) ->
                            properties.put(name, List.copyOf(values)));
                    view.put(block.getKey(), new Entry(properties, new TreeMap<>(block.getValue().defaults())));
                }
                return view;
            }
        };
    }
}
