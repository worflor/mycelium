package mycelium.random_keyed.v1.model;

/**
 * HMAC-SHA-256 (RFC 2104 over {@link Sha256}), written out (random.keyed@1, law K1): checked against RFC 4231's test cases
 * and against the JDK's {@code Mac("HmacSHA256")} in the harness. HMAC itself takes a key of any length (a longer one than
 * a block is hashed first); the 32-byte rule belongs to {@link KeyedDraw}.
 */
public final class HmacSha256 {
    /** SHA-256's block, in bytes. */
    private static final int BLOCK = 64;

    private HmacSha256() {
    }

    /** {@code H((K' ^ opad) || H((K' ^ ipad) || message))}, 32 bytes. Neither input is changed. */
    public static byte[] mac(byte[] key, byte[] message) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (message == null) {
            throw new NullPointerException("message");
        }
        byte[] k = key.length > BLOCK ? Sha256.digest(key) : key;
        byte[] inner = new byte[BLOCK + message.length];
        byte[] outer = new byte[BLOCK + Sha256.BYTES];
        for (int i = 0; i < BLOCK; i++) {
            byte b = i < k.length ? k[i] : 0;
            inner[i] = (byte) (b ^ 0x36);
            outer[i] = (byte) (b ^ 0x5c);
        }
        System.arraycopy(message, 0, inner, BLOCK, message.length);
        System.arraycopy(Sha256.digest(inner), 0, outer, BLOCK, Sha256.BYTES);
        return Sha256.digest(outer);
    }
}
