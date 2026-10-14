package mycelium.storage_access.v1.model;

/** A block position: the store's own block, not the block of whatever reaches it. */
public record Position(int x, int y, int z) {
}
