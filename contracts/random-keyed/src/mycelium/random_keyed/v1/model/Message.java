package mycelium.random_keyed.v1.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a derivation or a draw is taken over: a domain and typed fields (random.keyed@1, law K3). Immutable: each field
 * method returns a new message, so a prefix can be shared ({@code Message.of(CUT).uuid(player)}) and extended per draw.
 *
 * <p>The encoding ({@link #encoded()}) is the domain's UTF-8 bytes, one {@code 0x00}, then each field as a one-byte tag,
 * a four-byte big-endian length and its bytes: text as UTF-8, a {@code long} as 8 bytes big-endian, a UUID as its most
 * then least significant halves, raw bytes as given. It is injective: the domain cannot contain {@code 0x00}, so it ends
 * at the first one, and every field says its kind and its length. A message is checked when it is encoded, and every
 * derivation and draw encodes first, so a message that would be ambiguous is refused before it draws: an empty domain, a
 * domain containing U+0000, or text that is not well-formed UTF-16 (an unpaired surrogate, which a lenient encoder writes
 * as {@code ?} and so collides with a real {@code ?}).
 */
public final class Message {
    /** The kind of a field, and its tag in the encoding. */
    public enum Kind {
        TEXT(1), U64(2), UUID(3), BYTES(4);

        private final int tag;

        Kind(int tag) {
            this.tag = tag;
        }

        /** The byte that starts this kind of field in the encoding. */
        public int tag() {
            return tag;
        }
    }

    /** One field: its kind and its value ({@code String}, {@code Long}, {@code java.util.UUID} or a copy of the bytes). */
    public static final class Field {
        private final Kind kind;
        private final Object value;

        private Field(Kind kind, Object value) {
            this.kind = kind;
            this.value = value;
        }

        public Kind kind() {
            return kind;
        }

        /** The value as given: a {@code String}, a {@code Long}, a {@code UUID}, or a copy of the {@code byte[]}. */
        public Object value() {
            return value instanceof byte[] ? ((byte[]) value).clone() : value;
        }

        /** The field's bytes in the encoding, without its tag and length. Refuses text that is not well-formed. */
        public byte[] payload() {
            switch (kind) {
                case TEXT:
                    return utf8((String) value, "a text field");
                case U64:
                    return be64((Long) value);
                case UUID: {
                    UUID u = (UUID) value;
                    byte[] out = new byte[16];
                    System.arraycopy(be64(u.getMostSignificantBits()), 0, out, 0, 8);
                    System.arraycopy(be64(u.getLeastSignificantBits()), 0, out, 8, 8);
                    return out;
                }
                default:
                    return ((byte[]) value).clone();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Field)) {
                return false;
            }
            Field f = (Field) o;
            return kind == f.kind && (value instanceof byte[] && f.value instanceof byte[]
                    ? Arrays.equals((byte[]) value, (byte[]) f.value) : value.equals(f.value));
        }

        @Override
        public int hashCode() {
            return 31 * kind.hashCode() + (value instanceof byte[] ? Arrays.hashCode((byte[]) value) : value.hashCode());
        }

        @Override
        public String toString() {
            return kind + "(" + (value instanceof byte[] ? ((byte[]) value).length + " bytes" : value) + ")";
        }
    }

    private final String domain;
    private final List<Field> fields;

    private Message(String domain, List<Field> fields) {
        this.domain = domain;
        this.fields = fields;
    }

    /** A message in {@code domain}, with no fields yet. A domain names one kind of derivation, versioned in its name. */
    public static Message of(String domain) {
        return new Message(Objects.requireNonNull(domain, "domain"), Collections.<Field>emptyList());
    }

    public Message text(String value) {
        return with(new Field(Kind.TEXT, Objects.requireNonNull(value, "text")));
    }

    /** A {@code long}'s 64 bits (an {@code int} widens; a negative value is its two's complement). */
    public Message u64(long value) {
        return with(new Field(Kind.U64, value));
    }

    public Message uuid(UUID value) {
        return with(new Field(Kind.UUID, Objects.requireNonNull(value, "uuid")));
    }

    /** Raw bytes, copied: changing the array afterwards does not change the message. */
    public Message bytes(byte[] value) {
        return with(new Field(Kind.BYTES, Objects.requireNonNull(value, "bytes").clone()));
    }

    public String domain() {
        return domain;
    }

    public List<Field> fields() {
        return fields;
    }

    /** The domain's bytes in the encoding: UTF-8, refused if empty, containing U+0000, or not well-formed. */
    public byte[] domainBytes() {
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("a domain is not empty");
        }
        if (domain.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("a domain never contains U+0000: it ends the domain in the encoding");
        }
        return utf8(domain, "a domain");
    }

    /** The injective encoding (class comment). Refuses a message that would be ambiguous. */
    public byte[] encoded() {
        byte[] d = domainBytes();
        List<byte[]> payloads = new ArrayList<>(fields.size());
        int size = d.length + 1;
        for (Field f : fields) {
            byte[] p = f.payload();
            payloads.add(p);
            size += 5 + p.length;
        }
        byte[] out = new byte[size];
        System.arraycopy(d, 0, out, 0, d.length);
        int at = d.length + 1; // out[d.length] is the 0x00 that ends the domain
        for (int i = 0; i < fields.size(); i++) {
            byte[] p = payloads.get(i);
            out[at++] = (byte) fields.get(i).kind().tag();
            out[at++] = (byte) (p.length >>> 24);
            out[at++] = (byte) (p.length >>> 16);
            out[at++] = (byte) (p.length >>> 8);
            out[at++] = (byte) p.length;
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private Message with(Field field) {
        List<Field> next = new ArrayList<>(fields.size() + 1);
        next.addAll(fields);
        next.add(field);
        return new Message(domain, Collections.unmodifiableList(next));
    }

    /** Strict UTF-8: a lone surrogate is refused, never replaced. */
    static byte[] utf8(String s, String what) {
        byte[] out = new byte[s.length() * 3];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                out[n++] = (byte) c;
            } else if (c < 0x800) {
                out[n++] = (byte) (0xc0 | (c >>> 6));
                out[n++] = (byte) (0x80 | (c & 0x3f));
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                out[n++] = (byte) (0xf0 | (cp >>> 18));
                out[n++] = (byte) (0x80 | ((cp >>> 12) & 0x3f));
                out[n++] = (byte) (0x80 | ((cp >>> 6) & 0x3f));
                out[n++] = (byte) (0x80 | (cp & 0x3f));
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException(what + " is not well-formed UTF-16: an unpaired surrogate at " + i);
            } else {
                out[n++] = (byte) (0xe0 | (c >>> 12));
                out[n++] = (byte) (0x80 | ((c >>> 6) & 0x3f));
                out[n++] = (byte) (0x80 | (c & 0x3f));
            }
        }
        return Arrays.copyOf(out, n);
    }

    static byte[] be64(long v) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (v >>> (56 - 8 * i));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Message && domain.equals(((Message) o).domain) && fields.equals(((Message) o).fields);
    }

    @Override
    public int hashCode() {
        return 31 * domain.hashCode() + fields.hashCode();
    }

    @Override
    public String toString() {
        return "Message(" + domain + "; " + fields + ")";
    }
}
