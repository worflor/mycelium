package mycelium.bridges.block_catalog;

import mycelium.block_catalog.v1.model.BlockCatalog;
import mycelium.block_catalog.v1.model.CatalogSource;

/** Snapshot the initialized game's registry; no loader-specific registration or global cache. */
public final class MinecraftBlockCatalog implements CatalogSource {
    @Override
    public BlockCatalog capture() {
        return Ports.capture();
    }
}
