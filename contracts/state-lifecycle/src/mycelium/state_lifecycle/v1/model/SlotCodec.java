package mycelium.state_lifecycle.v1.model;

/**
 * How a durable slot's value becomes what storage holds, and back. The stored form is opaque to this contract; it must be
 * immutable and have value equality ({@code equals}), because storage compares and keeps it.
 *
 * <p>This contract does not import {@code item.component@1}; a consumer's seam adapts that contract's codec, which
 * brings its schema migration, unknown-field retention and quarantine with it: {@code encode} is the component codec's
 * {@code encode} of its frozen value, {@code decode} is its {@code decode} (a {@code CodecException} becomes a
 * {@link SlotCodecException}), and {@code same} is its {@code semanticEquals}.
 */
public interface SlotCodec<T> {
    Object encode(T value);

    T decode(Object stored) throws SlotCodecException;

    /** The codec's own equality of two values: what "survives equal" means for this slot. */
    boolean same(T a, T b);
}
