package mycelium.dimension_travel.v2.model;

public enum Reason {
    /** The player does not currently exist in the dimension {@code where(player)} reports. */
    NOT_FOUND,
    /** Move ran without throwing, but arrival could not be confirmed (target not actually reached). */
    TARGET_UNLOADED,
    /** The requested target is exactly the player's current position: nothing to do. */
    SAME_POSITION,
    /** @2: the traveller already has an open trip; a second departure would overwrite where home is. */
    TRIP_OPEN,
    /** @2: there is no open trip to return from or recover. */
    NO_TRIP
}
