package mycelium.random_keyed.v1.fixtures;

import mycelium.random_keyed.v1.model.KeyedRandom;
import mycelium.random_keyed.v1.model.Message;

/**
 * Does nothing: every digest, MAC and encoding is zeros, every draw and unit is zero and nothing is below any odds. It is
 * perfectly deterministic and every unit is in range, which is why it must be in the matrix: K1 says SHA-256 reproduces
 * the published vectors, so a hash that computes nothing fails.
 */
public final class NoOpKeyedRandom implements KeyedRandom {
    @Override
    public byte[] sha256(byte[] data) {
        return new byte[32];
    }

    @Override
    public byte[] hmacSha256(byte[] key, byte[] data) {
        return new byte[32];
    }

    @Override
    public byte[] encode(Message message) {
        return new byte[0];
    }

    @Override
    public long derive(Message message) {
        return 0;
    }

    @Override
    public long draw(byte[] key, Message message) {
        return 0;
    }

    @Override
    public long unit53(long bits) {
        return 0;
    }

    @Override
    public boolean below(long unit53, int permille) {
        return false;
    }
}
