package mycelium.state_lifecycle.v1.model;

import mycelium.item_component.v1.model.CodecException;
import mycelium.item_component.v1.model.ComponentCodec;
import mycelium.item_component.v1.model.TagNode;

import java.util.Objects;

/**
 * A durable slot's codec made from an {@code item.component@1} codec (this contract requires that one, and imports only
 * its model). The slot's stored form is the component codec's {@code TagNode}, so a {@code RecordCodec}'s retained unknown
 * fields, its sorted keys and its quarantining decode all apply to a slot as they do to an item component; the slot's
 * "equal after a round trip" is the component codec's {@code semanticEquals}.
 */
public final class ComponentSlotCodec<T> implements SlotCodec<T> {
    private final ComponentCodec<T> codec;

    private ComponentSlotCodec(ComponentCodec<T> codec) {
        this.codec = codec;
    }

    public static <T> SlotCodec<T> of(ComponentCodec<T> codec) {
        return new ComponentSlotCodec<>(Objects.requireNonNull(codec, "codec"));
    }

    @Override
    public Object encode(T value) {
        return codec.encode(codec.freeze(value));
    }

    @Override
    public T decode(Object stored) throws SlotCodecException {
        if (!(stored instanceof TagNode node)) {
            throw new SlotCodecException("not a TagNode: " + stored);
        }
        try {
            return codec.decode(node);
        } catch (CodecException e) {
            throw new SlotCodecException(e.getMessage());
        }
    }

    @Override
    public boolean same(T a, T b) {
        return codec.semanticEquals(a, b);
    }
}
