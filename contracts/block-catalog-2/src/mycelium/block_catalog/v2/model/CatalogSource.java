package mycelium.block_catalog.v2.model;

/** Native seam: capture one complete game-registry snapshot. */
@FunctionalInterface
public interface CatalogSource {
    BlockCatalog capture();
}
