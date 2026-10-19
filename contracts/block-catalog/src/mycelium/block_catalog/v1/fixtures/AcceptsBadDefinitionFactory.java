package mycelium.block_catalog.v1.fixtures;

import mycelium.block_catalog.v1.harness.BlockCatalogFactory;
import mycelium.block_catalog.v1.model.BlockCatalog;

import java.util.Map;

/** Wrongly hides malformed input by replacing it with an empty snapshot. */
public final class AcceptsBadDefinitionFactory implements BlockCatalogFactory {
    @Override
    public BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks) {
        try {
            return new BlockCatalog(version, blocks);
        } catch (IllegalArgumentException rejected) {
            return new BlockCatalog("fallback", Map.of());
        }
    }
}
