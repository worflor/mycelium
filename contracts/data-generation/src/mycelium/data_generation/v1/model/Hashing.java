package mycelium.data_generation.v1.model;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one canonical hashing of a generation (SPEC.md §3, G3). Fixed: an implementation that hashes otherwise breaks the
 * contract, and the harness pins it with golden values.
 *
 * <pre>
 * fingerprint(content) = hex(SHA-256(utf8(content)))
 * generation(interpreter, manifest) = hex(SHA-256(
 *     frame(DOMAIN) || frame(interpreter) || u32(n) ||
 *     for each (ref, fingerprint) of the manifest in {@link Ref#ORDER}: frame(ref.kind) || frame(ref.id) || frame(fingerprint)))
 * frame(s) = u32(byte length of utf8(s)) || utf8(s)
 * u32 = four bytes, big-endian; hex = lower-case; utf8 = strict (an unpaired surrogate is refused, never replaced)
 * </pre>
 *
 * The listing is length-framed, so it is injective; it is sorted by code point, so no map, list or offer order reaches
 * it; and the interpreter is in it, so a change of the code that gives the data its meaning is a change of name.
 */
public final class Hashing {
    /** The domain tag every generation hash starts with. */
    public static final String DOMAIN = "mycelium:data.generation@1";

    private Hashing() {
    }

    /** Lower-case hex SHA-256 of the content's strict UTF-8. */
    public static String fingerprint(String content) {
        MessageDigest d = sha256();
        d.update(utf8(content));
        return hex(d.digest());
    }

    /** The generation hash of a manifest (ref to fingerprint) under an interpreter. The map's own order is ignored. */
    public static String generation(String interpreter, Map<Ref, String> manifest) {
        TreeMap<Ref, String> sorted = new TreeMap<>(Ref.ORDER);
        sorted.putAll(manifest);
        MessageDigest d = sha256();
        frame(d, DOMAIN);
        frame(d, interpreter);
        d.update(u32(sorted.size()));
        for (Map.Entry<Ref, String> e : sorted.entrySet()) {
            frame(d, e.getKey().kind());
            frame(d, e.getKey().id());
            frame(d, e.getValue());
        }
        return hex(d.digest());
    }

    /** Strict UTF-8: an unpaired surrogate is refused, so two different strings never share bytes. */
    public static byte[] utf8(String s) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer b = encoder.encode(CharBuffer.wrap(s));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not well-formed UTF-16 (an unpaired surrogate)", e);
        }
    }

    static void requireWellFormed(String s, String what) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                    continue;
                }
                throw new IllegalArgumentException(what + " holds an unpaired surrogate");
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(what + " holds an unpaired surrogate");
            }
        }
    }

    private static void frame(MessageDigest d, String s) {
        byte[] bytes = utf8(s);
        d.update(u32(bytes.length));
        d.update(bytes);
    }

    private static byte[] u32(int n) {
        return new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) {
            b.append(Character.forDigit((x >>> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return b.toString();
    }
}
