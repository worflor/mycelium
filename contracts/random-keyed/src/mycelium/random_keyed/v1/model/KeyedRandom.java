package mycelium.random_keyed.v1.model;

/**
 * random.keyed@1 as one type, so an implementation can be held to the laws (SPEC.md). A consumer calls the static faces,
 * {@link Derive}, {@link KeyedDraw}, {@link Unit} and {@link Message#encoded()}, which are the definitions;
 * {@link ReferenceKeyedRandom} is this interface over them.
 */
public interface KeyedRandom {
    /** SHA-256 of {@code data} (K1, K2). */
    byte[] sha256(byte[] data);

    /** HMAC-SHA-256 of {@code data} under {@code key}, a key of any length (K1, K2). */
    byte[] hmacSha256(byte[] key, byte[] data);

    /** The injective encoding of a message, refusing an ambiguous one (K3). */
    byte[] encode(Message message);

    /** The public derivation: {@code Derive.u64} (K2, K3). */
    long derive(Message message);

    /** The keyed draw: {@code KeyedDraw.u64}, a 32-byte key (K2, K3, K4). */
    long draw(byte[] key, Message message);

    /** The unit of 64 bits: {@code Unit.of} (K5). */
    long unit53(long bits);

    /** Exact odds: {@code Unit.below} (K5, K6). */
    boolean below(long unit53, int permille);
}
