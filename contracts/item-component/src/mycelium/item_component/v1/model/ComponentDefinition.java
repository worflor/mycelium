package mycelium.item_component.v1.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Definition of a typed item component: its identifier token, its pure codec, and its default value.
 * Optionally binds channel policies and field-aware lenses.
 */
public record ComponentDefinition<T>(
        ComponentId id,
        ComponentCodec<T> codec,
        T defaultValue,
        boolean networkSynced,
        boolean affectsIdentity,
        Optional<FieldAwareCodec<T>> fieldAwareCodec
) {
    public ComponentDefinition(ComponentId id, ComponentCodec<T> codec, T defaultValue) {
        this(id, codec, defaultValue, !id.namespace().equals("server_internal"), true, Optional.empty());
    }

    public ComponentDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(fieldAwareCodec, "fieldAwareCodec");
        defaultValue = defaultValue != null ? codec.freeze(defaultValue) : null;
    }

    public ComponentDefinition<T> withNetworkSynced(boolean synced) {
        return new ComponentDefinition<>(id, codec, defaultValue, synced, affectsIdentity, fieldAwareCodec);
    }

    public ComponentDefinition<T> withAffectsIdentity(boolean identity) {
        return new ComponentDefinition<>(id, codec, defaultValue, networkSynced, identity, fieldAwareCodec);
    }

    public ComponentDefinition<T> withFieldAwareCodec(FieldAwareCodec<T> lens) {
        return new ComponentDefinition<>(id, codec, defaultValue, networkSynced, affectsIdentity, Optional.ofNullable(lens));
    }

    @Override
    public T defaultValue() {
        return defaultValue != null ? codec.freeze(defaultValue) : null;
    }

    public TagNode encodeValue(T value) {
        try {
            codec.validate(value);
        } catch (CodecException e) {
            throw new IllegalArgumentException("Validation failed for " + id + ": " + e.getMessage(), e);
        }
        return codec.encode(value);
    }

    public T decodeValue(TagNode node) throws CodecException {
        T decoded = codec.decode(node);
        if (decoded == null) {
            throw new CodecException("Decoder for " + id + " returned null");
        }
        codec.validate(decoded);
        return decoded;
    }

    public boolean isDefault(T value) {
        if (defaultValue == null) {
            return value == null;
        }
        return codec.semanticEquals(defaultValue, value);
    }
}
