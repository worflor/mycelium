package mycelium.state_lifecycle.v1.model;

/**
 * A crossing that cannot be planned or applied as asked: a host in the wrong state for the transition, a stored value
 * that does not decode. Applying a plan inside a {@link Tx} that is then aborted leaves nothing of it behind.
 */
public class CrossingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CrossingException(String message) {
        super(message);
    }

    public CrossingException(String message, Throwable cause) {
        super(message, cause);
    }
}
