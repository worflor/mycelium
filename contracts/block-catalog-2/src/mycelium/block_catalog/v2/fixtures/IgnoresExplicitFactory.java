package mycelium.block_catalog.v2.fixtures;

import mycelium.block_catalog.v2.harness.BlockCatalogFactory;
import mycelium.block_catalog.v2.model.BlockCatalog;

import java.util.Map;

/** Wrongly treats every requested state as the block's defaults. */
public final class IgnoresExplicitFactory implements BlockCatalogFactory {
    @Override
    public BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks) {
        return new BlockCatalog(version, blocks) {
            @Override
            public Map<String, String> normalize(String id, Map<String, String> explicitProperties) {
                return super.normalize(id, Map.of());
            }
        };
    }
}
