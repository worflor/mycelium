package mycelium.random_keyed.v1.model;

/** The reference {@link KeyedRandom}: the static faces, as one object. Keeps no state. */
public class ReferenceKeyedRandom implements KeyedRandom {
    @Override
    public byte[] sha256(byte[] data) {
        return Sha256.digest(data);
    }

    @Override
    public byte[] hmacSha256(byte[] key, byte[] data) {
        return HmacSha256.mac(key, data);
    }

    @Override
    public byte[] encode(Message message) {
        return message.encoded();
    }

    @Override
    public long derive(Message message) {
        return Derive.u64(message);
    }

    @Override
    public long draw(byte[] key, Message message) {
        return KeyedDraw.u64(key, message);
    }

    @Override
    public long unit53(long bits) {
        return Unit.of(bits);
    }

    @Override
    public boolean below(long unit53, int permille) {
        return Unit.below(unit53, permille);
    }
}
