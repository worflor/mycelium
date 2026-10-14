package mycelium.storage_access.v1.model;

/**
 * The side of a store that is touched: the face of the store's own block that the reaching thing stands against. A jack
 * east of a chest touches the chest's {@code EAST} face. Every access names a face; a store with no sides answers every face
 * alike.
 */
public enum Face {
    DOWN, UP, NORTH, SOUTH, WEST, EAST
}
