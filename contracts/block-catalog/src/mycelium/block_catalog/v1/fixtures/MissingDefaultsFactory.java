package mycelium.block_catalog.v1.fixtures;

import mycelium.block_catalog.v1.harness.BlockCatalogFactory;
import mycelium.block_catalog.v1.model.BlockCatalog;

import java.util.Map;

/** Wrongly reports only explicitly supplied properties and forgets the full default state. */
public final class MissingDefaultsFactory implements BlockCatalogFactory {
    @Override
    public BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks) {
        return new BlockCatalog(version, blocks) {
            @Override
            public Map<String, String> normalize(String id, Map<String, String> explicitProperties) {
                return Map.copyOf(explicitProperties);
            }
        };
    }
}
