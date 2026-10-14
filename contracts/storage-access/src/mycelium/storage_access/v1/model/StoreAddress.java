package mycelium.storage_access.v1.model;

import java.util.Objects;

/**
 * Where a store is found: a level (an opaque token, the dimension's id), the position of the store's block and the face
 * touched. Two addresses name the same store when level and position are equal, whatever their faces.
 */
public record StoreAddress(String level, Position position, Face face) {
    public StoreAddress {
        if (level == null || level.isBlank()) {
            throw new IllegalArgumentException("level must be non-blank");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(face, "face");
    }

    public static StoreAddress of(String level, int x, int y, int z, Face face) {
        return new StoreAddress(level, new Position(x, y, z), face);
    }

    /** The same store seen from another face. */
    public StoreAddress at(Face other) {
        return new StoreAddress(level, position, other);
    }

    /** Same level and position: one store, whichever faces are touched. */
    public boolean sameStoreAs(StoreAddress other) {
        return other != null && level.equals(other.level) && position.equals(other.position);
    }
}
