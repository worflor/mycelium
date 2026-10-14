package mycelium.random_keyed.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.random_keyed.v1.model.KeyedRandom;
import mycelium.random_keyed.v1.model.Message;
import mycelium.random_keyed.v1.model.Unit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * random.keyed@1: the laws of owned hashing, domain-separated derivation and keyed draws (SPEC.md). The JDK's SHA-256 and
 * HMAC are read here, as a witness, and never by the model.
 */
public final class RandomKeyedContractTests {
    private final KeyedRandom impl;

    public RandomKeyedContractTests(KeyedRandom impl) {
        this.impl = impl;
    }

    public Check run() {
        Check c = new Check();
        k1Vectors(c);
        k2MatchesJdk(c);
        k3Separation(c);
        k4KeyMatters(c);
        k5ExactUnits(c);
        k6Uniform(c);
        k7Deterministic(c);
        return c;
    }

    // --- K1: owned algorithms -------------------------------------------------------------------------------------

    /** FIPS 180-4's examples (with the empty message and the million a's NIST publishes beside them). */
    private void k1Vectors(Check c) {
        byte[] million = new byte[1_000_000];
        Arrays.fill(million, (byte) 'a');
        boolean sha = hex(impl.sha256(ascii(""))).equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                & hex(impl.sha256(ascii("abc"))).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                & hex(impl.sha256(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")))
                .equals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
                & hex(impl.sha256(ascii("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopq"
                + "klmnopqrlmnopqrsmnopqrstnopqrstu")))
                .equals("cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1")
                & hex(impl.sha256(million)).equals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
        c.that(sha, "K1:sha256-vectors");

        byte[] aa131 = filled(131, 0xaa);
        String[][] rfc4231 = {
                {hex(filled(20, 0x0b)), hex(ascii("Hi There")),
                        "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"},
                {hex(ascii("Jefe")), hex(ascii("what do ya want for nothing?")),
                        "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"},
                {hex(filled(20, 0xaa)), hex(filled(50, 0xdd)),
                        "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"},
                {"0102030405060708090a0b0c0d0e0f10111213141516171819", hex(filled(50, 0xcd)),
                        "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"},
                {hex(filled(20, 0x0c)), hex(ascii("Test With Truncation")),
                        "a3b6167473100ee06e0c796c2955552b"}, // case 5: the first 128 bits only
                {hex(aa131), hex(ascii("Test Using Larger Than Block-Size Key - Hash Key First")),
                        "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"},
                {hex(aa131), hex(ascii("This is a test using a larger than block-size key and a larger than block-size data."
                        + " The key needs to be hashed before being used by the HMAC algorithm.")),
                        "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2"},
        };
        boolean hmac = true;
        for (String[] t : rfc4231) {
            hmac &= hex(impl.hmacSha256(unhex(t[0]), unhex(t[1]))).startsWith(t[2]);
        }
        c.that(hmac, "K1:hmac-rfc4231");
    }

    // --- K2: agrees with the JDK ----------------------------------------------------------------------------------

    private void k2MatchesJdk(Check c) {
        Seeded r = new Seeded(0x6b32L);
        int[] keyLengths = {1, 4, 20, 31, 32, 33, 63, 64, 65, 131, 200};
        boolean same = true;
        for (int len = 0; len <= 300; len++) {
            byte[] data = randomBytes(r, len);
            same &= Arrays.equals(jdkSha(data), impl.sha256(data));
            byte[] key = randomBytes(r, keyLengths[len % keyLengths.length]);
            same &= Arrays.equals(jdkHmac(key, data), impl.hmacSha256(key, data));
        }
        c.that(same, "K2:matches-jdk");

        boolean derive = true;
        boolean draw = true;
        for (int i = 0; i < 300; i++) {
            Message m = randomMessage(r);
            byte[] key = randomBytes(r, 32);
            derive &= impl.derive(m) == Unit.first64(jdkSha(impl.encode(m)));
            draw &= impl.draw(key, m) == Unit.first64(jdkHmac(key, impl.encode(m)));
        }
        c.that(derive, "K2:derive-is-sha256");
        c.that(draw, "K2:draw-is-hmac");
    }

    // --- K3: domains and fields are separate ----------------------------------------------------------------------

    private void k3Separation(Check c) {
        // exhaustive over a small alphabet: every list of up to three fields in each of three domains
        List<Message> fieldOptions = new ArrayList<>();
        for (String s : new String[]{"", "a", "b", "ab", "\0"}) {
            fieldOptions.add(Message.of("x").text(s));
        }
        for (byte[] b : new byte[][]{{}, {0}, {0x61}, {0x61, 0x62}}) {
            fieldOptions.add(Message.of("x").bytes(b));
        }
        for (long v : new long[]{0, 1, 0x61}) {
            fieldOptions.add(Message.of("x").u64(v));
        }
        fieldOptions.add(Message.of("x").uuid(new UUID(0, 0)));
        fieldOptions.add(Message.of("x").uuid(new UUID(0, 0x61)));
        List<Message> all = new ArrayList<>();
        for (String domain : new String[]{"d", "e", "de"}) {
            List<Message> level = List.of(Message.of(domain));
            all.addAll(level);
            for (int depth = 0; depth < 3; depth++) {
                List<Message> next = new ArrayList<>();
                for (Message m : level) {
                    for (Message f : fieldOptions) {
                        next.add(append(m, f.fields().get(0)));
                    }
                }
                all.addAll(next);
                level = next;
            }
        }
        Map<String, Message> seen = new HashMap<>();
        boolean injective = true;
        for (Message m : all) {
            Message before = seen.put(hex(impl.encode(m)), m);
            injective &= before == null || before.equals(m);
        }
        Seeded r = new Seeded(0x6b33L);
        for (int i = 0; i < 5000; i++) {
            Message m = randomMessage(r);
            Message before = seen.put(hex(impl.encode(m)), m);
            injective &= before == null || before.equals(m);
        }
        c.that(injective, "K3:injective");

        boolean differ = true;
        byte[] key = randomBytes(r, 32);
        for (int i = 0; i < 300; i++) {
            Message m = randomMessage(r);
            Message a = withDomain("test:a:1", m);
            Message b = withDomain("test:b:1", m);
            differ &= !Arrays.equals(impl.encode(a), impl.encode(b)) && impl.derive(a) != impl.derive(b)
                    && impl.draw(key, a) != impl.draw(key, b);
        }
        c.that(differ, "K3:domains-differ");

        String golden = "6400" + "01000000026162" + "02000000080000000000000001"
                + "0300000010" + "00000000000000020000000000000003" + "040000000109";
        String goldenUnicode = "c3a900" + "0100000004f09d849e" + "0200000008ffffffffffffffff";
        c.that(hex(impl.encode(Message.of("d").text("ab").u64(1).uuid(new UUID(2, 3)).bytes(new byte[]{9}))).equals(golden)
                && hex(impl.encode(Message.of("é").text("𝄞").u64(-1))).equals(goldenUnicode)
                && hex(impl.encode(Message.of("d"))).equals("6400"), "K3:encoding-exact");

        Message[] ambiguous = {
                Message.of(""), Message.of("a\0b"), Message.of("\ud800"), Message.of("d").text("\ud800"),
                Message.of("d").text("\udc00"), Message.of("d").text("\udc00\ud800"), Message.of("d").text("a\ud800b"),
                Message.of("d").u64(1).text("\udfff"),
        };
        boolean refused = true;
        for (Message m : ambiguous) {
            refused &= throwsOn(() -> impl.encode(m)) && throwsOn(() -> impl.derive(m))
                    && throwsOn(() -> impl.draw(key, m));
        }
        c.that(refused, "K3:refuses-ambiguous");
    }

    // --- K4: the key matters --------------------------------------------------------------------------------------

    private void k4KeyMatters(Check c) {
        Seeded r = new Seeded(0x6b34L);
        boolean sensitive = true;
        for (int k = 0; k < 8; k++) {
            byte[] key = randomBytes(r, 32);
            for (int j = 0; j < 4; j++) {
                Message m = randomMessage(r);
                long base = impl.draw(key, m);
                for (int bit = 0; bit < 256; bit++) {
                    byte[] flipped = key.clone();
                    flipped[bit / 8] ^= (byte) (1 << (bit % 8));
                    sensitive &= impl.draw(flipped, m) != base;
                }
            }
        }
        c.that(sensitive, "K4:key-sensitive");

        boolean keyed = true;
        for (int j = 0; j < 8; j++) {
            Message m = randomMessage(r);
            Set<Long> draws = new HashSet<>();
            for (int k = 0; k < 64; k++) {
                long d = impl.draw(randomBytes(r, 32), m);
                draws.add(d);
                keyed &= d != impl.derive(m);
            }
            keyed &= draws.size() == 64;
        }
        c.that(keyed, "K4:not-unkeyed");

        // one draw and the public inputs forecast no other draw under the same key
        boolean secret = true;
        for (int i = 0; i < 300; i++) {
            byte[] key = randomBytes(r, 32);
            Message a = randomMessage(r).u64(i);
            Message b = randomMessage(r).u64(i + 1_000_000L);
            secret &= (impl.draw(key, a) ^ impl.draw(key, b)) != (impl.derive(a) ^ impl.derive(b));
        }
        c.that(secret, "K4:one-draw-forecasts-no-other");

        Message m = Message.of("test:key:1").u64(7);
        boolean lengths = !throwsOn(() -> impl.draw(new byte[32], m));
        for (int len : new int[]{0, 1, 16, 31, 33, 64}) {
            lengths &= throwsOn(() -> impl.draw(new byte[len], m));
        }
        c.that(lengths, "K4:key-length");
    }

    // --- K5: exact units ------------------------------------------------------------------------------------------

    private void k5ExactUnits(Check c) {
        Seeded r = new Seeded(0x6b35L);
        long[] edges = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x7ffL, 0x800L, -0x800L};
        boolean range = true;
        boolean high = true;
        for (int i = 0; i < 10_000 + edges.length; i++) {
            long bits = i < edges.length ? edges[i] : r.nextLong();
            long u = impl.unit53(bits);
            range &= u >= 0 && u < Unit.ONE;
            high &= u == bits >>> 11;
        }
        for (int i = 0; i < 200; i++) {
            byte[] key = randomBytes(r, 32);
            Message m = randomMessage(r);
            high &= impl.unit53(impl.draw(key, m)) == Unit.first64(jdkHmac(key, impl.encode(m))) >>> 11;
        }
        c.that(range, "K5:range");
        c.that(high, "K5:high-bits");

        // every per mille at its edge: the last unit below the odds and the first one not, decided with no float
        BigInteger one = BigInteger.ONE.shiftLeft(53);
        BigInteger thousand = BigInteger.valueOf(1000);
        boolean exact = true;
        for (int p = 0; p <= 1000; p++) {
            BigInteger scaled = BigInteger.valueOf(p).multiply(one);
            long t = scaled.add(thousand.subtract(BigInteger.ONE)).divide(thousand).longValueExact(); // ceil(p * 2^53 / 1000)
            for (long u : new long[]{t - 2, t - 1, t, t + 1}) {
                if (u < 0 || u >= Unit.ONE) {
                    continue;
                }
                boolean truth = BigInteger.valueOf(u).multiply(thousand).compareTo(scaled) < 0;
                exact &= impl.below(u, p) == truth;
            }
        }
        for (int i = 0; i < 1000; i++) {
            long u = r.nextLong() >>> 11;
            exact &= !impl.below(u, 0) && impl.below(u, 1000);
        }
        exact &= !impl.below(0, 0) && impl.below(Unit.ONE - 1, 1000) && impl.below(0, 1) && !impl.below(Unit.ONE - 1, 999);
        c.that(exact, "K5:edges-exact");

        c.that(throwsOn(() -> impl.below(0, -1)) && throwsOn(() -> impl.below(0, 1001))
                && throwsOn(() -> impl.below(-1, 500)) && throwsOn(() -> impl.below(Unit.ONE, 500)), "K5:refuses");
    }

    // --- K6: uniform ----------------------------------------------------------------------------------------------

    private void k6Uniform(Check c) {
        Seeded r = new Seeded(0x6b36L);
        byte[] key = randomBytes(r, 32);
        int n = 200_000;
        int[] buckets = new int[64];
        int[] odds = {150, 500, 600};
        int[] hits = new int[odds.length];
        Message prefix = Message.of("test:uniform:1").uuid(new UUID(r.nextLong(), r.nextLong()));
        for (int i = 0; i < n; i++) {
            long u = impl.unit53(impl.draw(key, prefix.u64(i)));
            if (u < 0 || u >= Unit.ONE) {
                c.fail("K6:uniform (a unit out of range: " + u + ")");
                return;
            }
            buckets[(int) (u >>> 47)]++;
            for (int j = 0; j < odds.length; j++) {
                hits[j] += impl.below(u, odds[j]) ? 1 : 0;
            }
        }
        double expected = n / 64.0;
        double chi = 0;
        for (int b : buckets) {
            chi += (b - expected) * (b - expected) / expected;
        }
        boolean uniform = chi < 103.442; // chi-squared under p = 0.001 at 63 degrees of freedom
        for (int j = 0; j < odds.length; j++) {
            uniform &= Math.abs(hits[j] / (double) n - odds[j] / 1000.0) <= 0.01;
        }
        c.that(uniform, "K6:uniform");
    }

    // --- K7: determinism ------------------------------------------------------------------------------------------

    private void k7Deterministic(Check c) {
        Seeded r = new Seeded(0x6b37L);
        boolean same = true;
        for (int i = 0; i < 200; i++) {
            byte[] key = randomBytes(r, 32);
            byte[] keyBefore = key.clone();
            Message m = randomMessage(r);
            long draw = impl.draw(key, m);
            long derive = impl.derive(m);
            byte[] encoded = impl.encode(m);
            impl.draw(randomBytes(r, 32), randomMessage(r)); // other calls in between must not move anything
            impl.derive(randomMessage(r));
            same &= impl.draw(key, m) == draw && impl.derive(m) == derive && Arrays.equals(impl.encode(m), encoded)
                    && Arrays.equals(key, keyBefore);
        }
        // a message is a value: extending a shared prefix never changes it
        Message prefix = Message.of("test:prefix:1").u64(1);
        byte[] before = impl.encode(prefix);
        Message a = prefix.u64(2);
        Message b = prefix.u64(3);
        byte[] raw = {1, 2, 3};
        Message withRaw = prefix.bytes(raw);
        byte[] rawEncoded = impl.encode(withRaw);
        raw[0] = 9;
        same &= Arrays.equals(impl.encode(prefix), before) && !Arrays.equals(impl.encode(a), impl.encode(b))
                && Arrays.equals(impl.encode(withRaw), rawEncoded);
        c.that(same, "K7:deterministic");
    }

    // --- inputs ---------------------------------------------------------------------------------------------------

    private static final String[] DOMAINS = {"test:a:1", "test:b:1", "x", "ü:é:1", "example:session:1"};
    private static final String[] TEXTS = {"", "a", "ab", "\0", "?", "é", "月", "𝄞", "a\u0000b"};

    /** A well-formed message with zero to four fields of every kind. */
    static Message randomMessage(Seeded r) {
        Message m = Message.of(DOMAINS[r.nextInt(DOMAINS.length)]);
        int fields = r.nextInt(5);
        for (int i = 0; i < fields; i++) {
            switch (r.nextInt(4)) {
                case 0 -> {
                    StringBuilder s = new StringBuilder();
                    int parts = r.nextInt(4);
                    for (int j = 0; j < parts; j++) {
                        s.append(TEXTS[r.nextInt(TEXTS.length)]);
                    }
                    m = m.text(s.toString());
                }
                case 1 -> m = m.u64(r.nextBoolean() ? r.nextInt(4) : r.nextLong());
                case 2 -> m = m.uuid(new UUID(r.nextLong(), r.nextLong()));
                default -> m = m.bytes(randomBytes(r, r.nextInt(12)));
            }
        }
        return m;
    }

    static Message append(Message m, Message.Field f) {
        return switch (f.kind()) {
            case TEXT -> m.text((String) f.value());
            case U64 -> m.u64((Long) f.value());
            case UUID -> m.uuid((UUID) f.value());
            case BYTES -> m.bytes((byte[]) f.value());
        };
    }

    static Message withDomain(String domain, Message m) {
        Message out = Message.of(domain);
        for (Message.Field f : m.fields()) {
            out = append(out, f);
        }
        return out;
    }

    static byte[] randomBytes(Seeded r, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) r.nextInt(256);
        }
        return b;
    }

    static byte[] filled(int n, int value) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) value);
        return b;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Character.forDigit((x >>> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return s.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static boolean throwsOn(Runnable body) {
        try {
            body.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    static byte[] jdkSha(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this JDK has no SHA-256", e);
        }
    }

    static byte[] jdkHmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this JDK has no HmacSHA256", e);
        }
    }
}
