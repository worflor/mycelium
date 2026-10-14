package mycelium.random_keyed.v1.model;

/**
 * SHA-256 (FIPS 180-4), written out (random.keyed@1, law K1). Owned rather than looked up: a {@code MessageDigest} is a
 * provider lookup that the environment configures and that may throw, and a derivation must mean the same thing on every
 * Java a target runs on and after every refactor. The harness holds it to the published vectors (K1) and to the JDK's own
 * implementation (K2); the model never reads the JDK's.
 */
public final class Sha256 {
    /** Bytes in a digest. */
    public static final int BYTES = 32;

    private static final int[] K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private static final int[] H0 = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };

    private Sha256() {
    }

    /** The digest of {@code data}, 32 bytes. The input is not changed. */
    public static byte[] digest(byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        // padding: a 1 bit, zeros, then the message length in bits as 64 bits big-endian, to a multiple of 64 bytes
        int blocks = (data.length + 9 + 63) / 64;
        byte[] padded = new byte[blocks * 64];
        System.arraycopy(data, 0, padded, 0, data.length);
        padded[data.length] = (byte) 0x80;
        long bits = (long) data.length * 8;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
        }

        int[] h = H0.clone();
        int[] w = new int[64];
        for (int block = 0; block < blocks; block++) {
            int off = block * 64;
            for (int t = 0; t < 16; t++) {
                int p = off + 4 * t;
                w[t] = (padded[p] & 0xff) << 24 | (padded[p + 1] & 0xff) << 16 | (padded[p + 2] & 0xff) << 8
                        | (padded[p + 3] & 0xff);
            }
            for (int t = 16; t < 64; t++) {
                int s0 = Integer.rotateRight(w[t - 15], 7) ^ Integer.rotateRight(w[t - 15], 18) ^ (w[t - 15] >>> 3);
                int s1 = Integer.rotateRight(w[t - 2], 17) ^ Integer.rotateRight(w[t - 2], 19) ^ (w[t - 2] >>> 10);
                w[t] = w[t - 16] + s0 + w[t - 7] + s1;
            }
            int a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
            for (int t = 0; t < 64; t++) {
                int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
                int ch = (e & f) ^ (~e & g);
                int t1 = hh + s1 + ch + K[t] + w[t];
                int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
                int maj = (a & b) ^ (a & c) ^ (b & c);
                int t2 = s0 + maj;
                hh = g;
                g = f;
                f = e;
                e = d + t1;
                d = c;
                c = b;
                b = a;
                a = t1 + t2;
            }
            h[0] += a;
            h[1] += b;
            h[2] += c;
            h[3] += d;
            h[4] += e;
            h[5] += f;
            h[6] += g;
            h[7] += hh;
        }

        byte[] out = new byte[BYTES];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (h[i] >>> 24);
            out[4 * i + 1] = (byte) (h[i] >>> 16);
            out[4 * i + 2] = (byte) (h[i] >>> 8);
            out[4 * i + 3] = (byte) h[i];
        }
        return out;
    }
}
