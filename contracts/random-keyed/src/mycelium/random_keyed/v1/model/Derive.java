package mycelium.random_keyed.v1.model;

/**
 * Public, one-way derivation (random.keyed@1): the first 8 bytes of {@code SHA-256(message)}, big-endian. Anyone with the
 * inputs can compute it, which is the point (a calendar salt every client evaluates the sky with); no one can run it
 * backwards, which is also the point (the salt does not hand out the seed it came from). Each kind of derivation is its
 * own domain, so no two share a value and adding one never moves another.
 */
public final class Derive {
    private Derive() {
    }

    /** The first 64 bits of {@code SHA-256(message.encoded())}. */
    public static long u64(Message message) {
        return Unit.first64(Sha256.digest(message.encoded()));
    }

    /** {@code Unit.of(u64(message))}: a public unit, for public odds ({@code Unit.below}). */
    public static long unit53(Message message) {
        return Unit.of(u64(message));
    }
}
