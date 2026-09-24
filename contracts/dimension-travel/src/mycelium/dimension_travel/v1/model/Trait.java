package mycelium.dimension_travel.v1.model;

public enum Trait {
    /** Arrival position matches the requested target exactly (no rounding, no snapping). */
    POSITION_EXACT,
    /** The player's inventory is unaffected by the move itself. */
    INVENTORY_PRESERVING
}
