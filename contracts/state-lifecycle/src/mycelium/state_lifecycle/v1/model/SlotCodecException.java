package mycelium.state_lifecycle.v1.model;

/** A stored form that does not decode. */
public final class SlotCodecException extends Exception {
    private static final long serialVersionUID = 1L;

    public SlotCodecException(String message) {
        super(message);
    }
}
