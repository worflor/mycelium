package mycelium.block_catalog.v1.fixtures;

import mycelium.block_catalog.v1.harness.BlockCatalogFactory;
import mycelium.block_catalog.v1.model.BlockCatalog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wrongly returns a mutable top-level map and mutable nested input views. */
public final class MutableOutputsFactory implements BlockCatalogFactory {
    @Override
    public BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks) {
        BlockCatalog immutable = new BlockCatalog(version, blocks);
        return new BlockCatalog(version, blocks) {
            @Override
            public Map<String, Entry> blocks() {
                return new LinkedHashMap<>(immutable.blocks());
            }
        };
    }
}
