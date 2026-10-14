package mycelium.random_keyed.v1.model;

/**
 * A keyed draw (random.keyed@1): the first 8 bytes of {@code HMAC-SHA-256(key, message)}, big-endian. Whoever knows every
 * public input (the domain, a counter, a player) and not the key cannot forecast it, and one draw tells nothing about
 * another. The key is 32 bytes the platform makes once with a secure generator (which the pure layer may not call) and
 * passes in; any other length is refused. The key is read, never kept or changed.
 */
public final class KeyedDraw {
    /** Bytes in a key. */
    public static final int KEY_BYTES = 32;

    private KeyedDraw() {
    }

    /** The first 64 bits of {@code HMAC-SHA-256(key, message.encoded())}. */
    public static long u64(byte[] key, Message message) {
        return Unit.first64(HmacSha256.mac(checkKey(key), message.encoded()));
    }

    /** {@code Unit.of(u64(key, message))}, in {@code [0, 2^53)}: compare it with odds by {@link Unit#below}. */
    public static long unit53(byte[] key, Message message) {
        return Unit.of(u64(key, message));
    }

    /** Returns the key if it is 32 bytes; refuses it otherwise. */
    public static byte[] checkKey(byte[] key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("a key is " + KEY_BYTES + " bytes, not " + key.length);
        }
        return key;
    }
}
