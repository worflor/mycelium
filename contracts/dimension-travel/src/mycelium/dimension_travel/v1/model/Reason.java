package mycelium.dimension_travel.v1.model;

public enum Reason {
    /** The player does not currently exist in the dimension {@code where(player)} reports. */
    NOT_FOUND,
    /** Move ran without throwing, but arrival could not be confirmed (target not actually reached). */
    TARGET_UNLOADED,
    /** The requested target is exactly the player's current position: nothing to do. */
    SAME_POSITION
}
