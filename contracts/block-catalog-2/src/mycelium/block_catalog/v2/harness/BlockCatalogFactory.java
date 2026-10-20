package mycelium.block_catalog.v2.harness;

import mycelium.block_catalog.v2.model.BlockCatalog;

import java.util.Map;

/** Test seam for applying the same catalog laws to deliberately broken implementations. */
@FunctionalInterface
public interface BlockCatalogFactory {
    BlockCatalog create(String version, Map<String, BlockCatalog.Entry> blocks);
}
