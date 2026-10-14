package mycelium.random_keyed.v1.fixtures;

import mycelium.random_keyed.v1.model.HmacSha256;
import mycelium.random_keyed.v1.model.KeyedDraw;
import mycelium.random_keyed.v1.model.KeyedRandom;
import mycelium.random_keyed.v1.model.Message;
import mycelium.random_keyed.v1.model.ReferenceKeyedRandom;
import mycelium.random_keyed.v1.model.Sha256;
import mycelium.random_keyed.v1.model.Unit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Broken implementations, each the reference with one rule wrong: the mistake really made (SPEC.md, rejection matrix). */
public final class KeyedVariants {
    private KeyedVariants() {
    }

    /** Hashes the message without the key: anyone with the public inputs can forecast every draw (K4). */
    public static KeyedRandom unkeyedDraw() {
        return new ReferenceKeyedRandom() {
            @Override
            public long draw(byte[] key, Message message) {
                KeyedDraw.checkKey(key);
                return Unit.first64(Sha256.digest(message.encoded()));
            }
        };
    }

    /** Keys the MAC with only the key's first 16 bytes: half the secret does nothing (K4). */
    public static KeyedRandom shortKeyDraw() {
        return new ReferenceKeyedRandom() {
            @Override
            public long draw(byte[] key, Message message) {
                return Unit.first64(HmacSha256.mac(Arrays.copyOf(KeyedDraw.checkKey(key), 16), message.encoded()));
            }
        };
    }

    /**
     * Mixes the key into the public hash with XOR. Every bit of the key changes every draw, and yet one draw and its public
     * inputs give away the key's fold, and with it every other draw under that key (K4).
     */
    public static KeyedRandom keyXorDraw() {
        return new ReferenceKeyedRandom() {
            @Override
            public long draw(byte[] key, Message message) {
                KeyedDraw.checkKey(key);
                long fold = 0;
                for (int i = 0; i < key.length; i += 8) {
                    fold ^= Unit.first64(Arrays.copyOfRange(key, i, i + 8));
                }
                return derive(message) ^ fold;
            }
        };
    }

    /** Accepts a key of any length: an empty or truncated key draws silently instead of being refused (K4). */
    public static KeyedRandom lenientKeyDraw() {
        return new ReferenceKeyedRandom() {
            @Override
            public long draw(byte[] key, Message message) {
                return Unit.first64(HmacSha256.mac(key, message.encoded()));
            }
        };
    }

    /** Joins the fields with no tag or length: ("ab", "c") and ("a", "bc") collide (K3). */
    public static KeyedRandom concatenatingMessage() {
        return new Encoding() {
            @Override
            public byte[] encode(Message message) {
                ByteArrayOutputStream out = start(message);
                for (Message.Field f : message.fields()) {
                    out.write(f.payload(), 0, f.payload().length);
                }
                return out.toByteArray();
            }
        };
    }

    /** Lengths but no tags: the text "a" and the byte 0x61 collide (K3). */
    public static KeyedRandom untaggedMessage() {
        return new Encoding() {
            @Override
            public byte[] encode(Message message) {
                ByteArrayOutputStream out = start(message);
                for (Message.Field f : message.fields()) {
                    byte[] p = f.payload();
                    out.write(p.length >>> 24);
                    out.write(p.length >>> 16);
                    out.write(p.length >>> 8);
                    out.write(p.length);
                    out.write(p, 0, p.length);
                }
                return out.toByteArray();
            }
        };
    }

    /** Leaves the domain out of the encoding: the same fields draw the same in every domain (K3). */
    public static KeyedRandom domainBlindMessage() {
        return new Encoding() {
            @Override
            public byte[] encode(Message message) {
                byte[] all = message.encoded();
                return Arrays.copyOfRange(all, message.domainBytes().length + 1, all.length);
            }
        };
    }

    /** Lengths little-endian: still injective, and every value derived before means something else (K3). */
    public static KeyedRandom littleEndianMessage() {
        return new Encoding() {
            @Override
            public byte[] encode(Message message) {
                ByteArrayOutputStream out = start(message);
                for (Message.Field f : message.fields()) {
                    byte[] p = f.payload();
                    out.write(f.kind().tag());
                    out.write(p.length);
                    out.write(p.length >>> 8);
                    out.write(p.length >>> 16);
                    out.write(p.length >>> 24);
                    out.write(p, 0, p.length);
                }
                return out.toByteArray();
            }
        };
    }

    /** Encodes text with the JDK's lenient encoder: an unpaired surrogate becomes '?' and collides with a real one (K3). */
    public static KeyedRandom lossyTextMessage() {
        return new Encoding() {
            @Override
            public byte[] encode(Message message) {
                String d = message.domain();
                if (d.isEmpty() || d.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("bad domain");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] db = d.getBytes(StandardCharsets.UTF_8);
                out.write(db, 0, db.length);
                out.write(0);
                for (Message.Field f : message.fields()) {
                    byte[] p = f.kind() == Message.Kind.TEXT
                            ? ((String) f.value()).getBytes(StandardCharsets.UTF_8) : f.payload();
                    out.write(f.kind().tag());
                    out.write(p.length >>> 24);
                    out.write(p.length >>> 16);
                    out.write(p.length >>> 8);
                    out.write(p.length);
                    out.write(p, 0, p.length);
                }
                return out.toByteArray();
            }
        };
    }

    /** Compares {@code u / 2^53 < p / 1000.0} in doubles: p / 1000 rounds, and a unit at the edge is decided wrongly (K5). */
    public static KeyedRandom floatUnit() {
        return new ReferenceKeyedRandom() {
            @Override
            public boolean below(long unit53, int permille) {
                if (unit53 < 0 || unit53 >= Unit.ONE || permille < 0 || permille > 1000) {
                    throw new IllegalArgumentException("out of range");
                }
                return unit53 / (double) Unit.ONE < permille / 1000.0;
            }
        };
    }

    /** Takes the low 53 bits of the first 8 bytes instead of the high (K5). */
    public static KeyedRandom lowBitsUnit() {
        return new ReferenceKeyedRandom() {
            @Override
            public long unit53(long bits) {
                return bits & (Unit.ONE - 1);
            }
        };
    }

    /** Shifts with sign ({@code >>}, not {@code >>>}): half of all units are negative (K5). */
    public static KeyedRandom signedShiftUnit() {
        return new ReferenceKeyedRandom() {
            @Override
            public long unit53(long bits) {
                return bits >> 11;
            }
        };
    }

    /** Clamps odds and units instead of refusing them: a ruleset's 1200 per mille is read as certain, silently (K5). */
    public static KeyedRandom lenientUnit() {
        return new ReferenceKeyedRandom() {
            @Override
            public boolean below(long unit53, int permille) {
                long u = Math.max(0, Math.min(Unit.ONE - 1, unit53));
                int p = Math.max(0, Math.min(1000, permille));
                return u * 1000 < (long) p << Unit.BITS;
            }
        };
    }

    /** Mixes a call counter into the draw: the same attempt, retried, rerolls (K7). */
    public static KeyedRandom statefulDraw() {
        return new ReferenceKeyedRandom() {
            private long calls;

            @Override
            public long draw(byte[] key, Message message) {
                return super.draw(key, message.u64(calls++));
            }
        };
    }

    /** A reference whose derivation and draw go through its own {@link #encode}, for the encoding variants. */
    private abstract static class Encoding extends ReferenceKeyedRandom {
        @Override
        public long derive(Message message) {
            return Unit.first64(Sha256.digest(encode(message)));
        }

        @Override
        public long draw(byte[] key, Message message) {
            return Unit.first64(HmacSha256.mac(KeyedDraw.checkKey(key), encode(message)));
        }

        /** The domain and its 0x00, as the reference writes them. */
        static ByteArrayOutputStream start(Message message) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] d = message.domainBytes();
            out.write(d, 0, d.length);
            out.write(0);
            return out;
        }
    }
}
