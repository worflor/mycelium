package mycelium.block_catalog.v2.fixtures;

import mycelium.block_catalog.v2.harness.BlockCatalogFactory;
import mycelium.block_catalog.v2.model.BlockCatalog;
import mycelium.block_catalog.v2.model.CatalogSource;

import java.util.Map;

/** The simplest inert source returns an empty catalog, regardless of the definitions offered to its factory seam. */
public final class NoOpCatalogSource implements BlockCatalogFactory, CatalogSource {
    @Override
    public BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks) {
        return new BlockCatalog(version, Map.of());
    }

    @Override
    public BlockCatalog capture() {
        return new BlockCatalog("26.3", Map.of());
    }
}
