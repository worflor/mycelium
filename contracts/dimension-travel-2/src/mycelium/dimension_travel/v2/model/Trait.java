package mycelium.dimension_travel.v2.model;

public enum Trait {
    /** Arrival position matches the requested target exactly (no rounding, no snapping). */
    POSITION_EXACT,
    /** The player's inventory is unaffected by the move itself. */
    INVENTORY_PRESERVING,
    /** @2: the port keeps a durable trip journal that survives a restart (a crash, a kill, a power cut). */
    JOURNALED
}
