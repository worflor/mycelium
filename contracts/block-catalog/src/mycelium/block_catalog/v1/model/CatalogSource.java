package mycelium.block_catalog.v1.model;

/** Native seam: capture one complete game-registry snapshot. */
@FunctionalInterface
public interface CatalogSource {
    BlockCatalog capture();
}
