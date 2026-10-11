package mycelium.item_component.v1.model;

import java.util.Objects;

/**
 * Definition of a typed item component: its identifier token, its pure codec, and its default value.
 */
public record ComponentDefinition<T>(
        ComponentId id,
        ComponentCodec<T> codec,
        T defaultValue
) {
    public ComponentDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(codec, "codec");
        defaultValue = codec.freeze(defaultValue);
    }

    @Override
    public T defaultValue() {
        return codec.freeze(defaultValue);
    }

    public TagNode encodeValue(T value) {
        return codec.encode(value);
    }

    public T decodeValue(TagNode node) throws CodecException {
        return codec.decode(node);
    }

    public boolean isDefault(T value) {
        return codec.semanticEquals(defaultValue, value);
    }
}

