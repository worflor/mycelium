package mycelium.world_clock.v1.model;

/** What moving a clock to a marker did (26.x's {@code ServerClockManager.MoveResult}; its {@code NO_TIME_MARKER_FOUND} is {@code NO_SUCH_MARKER}). */
public enum MoveResult {
    /** The clock has no such marker; nothing changed. */
    NO_SUCH_MARKER,
    /** The clock is exactly at the marker; nothing changed. */
    NOT_MOVED,
    /** The clock moved to the marker. */
    MOVED
}
