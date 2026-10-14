package mycelium.storage_access.v1.model;

/**
 * What a store keeps, each in the contract's one declared unit: {@code ITEM} in items, {@code FLUID} in thousandths of a
 * bucket ({@link FluidUnit#DECLARED}). A loader looks each kind up apart (Fabric's item and fluid lookups, NeoForge's and
 * Forge's item and fluid capabilities), so a store is of one kind.
 */
public enum Kind {
    ITEM, FLUID
}
