package mycelium.item_component.v1.model;

/** Checked exception thrown when component data fails to decode from a {@link TagNode}. */
public class CodecException extends Exception {
    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
