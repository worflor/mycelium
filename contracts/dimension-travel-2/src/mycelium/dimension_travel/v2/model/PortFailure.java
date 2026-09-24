package mycelium.dimension_travel.v2.model;

/** Thrown by {@link TravelPort#move} when it cannot confirm the move completed; may have partially acted. */
public class PortFailure extends RuntimeException {
    public PortFailure(String message) {
        super(message);
    }
}
