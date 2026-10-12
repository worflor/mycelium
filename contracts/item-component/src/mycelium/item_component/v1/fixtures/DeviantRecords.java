package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Base of the broken record bindings: every step delegates to the real {@link RecordCodec}; a subclass overrides the
 * one step it gets wrong.
 */
abstract class DeviantRecords implements RecordBinding {

    TagNode encode(RecordCodec<Object> real, Object value) {
        return real.encode(value);
    }

    Object decode(RecordCodec<Object> real, TagNode node, Object fallback) throws CodecException {
        return real.decode(node);
    }

    FieldAwareCodec.DecodedResult<Object> decodeFieldAware(RecordCodec<Object> real, TagNode.TagCompound raw,
                                                           Object fallback) throws CodecException {
        return real.decodeFieldAware(raw);
    }

    TagNode.TagCompound updateFieldAware(RecordCodec<Object> real, TagNode.TagCompound original, Object value)
            throws CodecException {
        return real.updateFieldAware(original, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public final <T> ComponentDefinition<T> bind(ComponentId id, RecordCodec<T> codec, T defaultValue) {
        final RecordCodec<Object> real = (RecordCodec<Object>) codec;
        final Object fallback = defaultValue;
        final DeviantRecords self = this;

        class Broken implements ComponentCodec<Object>, FieldAwareCodec<Object> {
            @Override
            public TagNode encode(Object value) {
                return self.encode(real, value);
            }

            @Override
            public Object decode(TagNode node) throws CodecException {
                return self.decode(real, node, fallback);
            }

            @Override
            public DecodedResult<Object> decodeFieldAware(TagNode.TagCompound raw) throws CodecException {
                return self.decodeFieldAware(real, raw, fallback);
            }

            @Override
            public TagNode.TagCompound updateFieldAware(TagNode.TagCompound original, Object value) throws CodecException {
                return self.updateFieldAware(real, original, value);
            }

            @Override
            public Object freeze(Object value) {
                return real.freeze(value);
            }

            @Override
            public boolean semanticEquals(Object a, Object b) {
                return real.semanticEquals(a, b);
            }

            @Override
            public int semanticHash(Object value) {
                return real.semanticHash(value);
            }
        }

        Broken broken = new Broken();
        return (ComponentDefinition<T>) new ComponentDefinition<Object>(id, broken, fallback).withFieldAwareCodec(broken);
    }
}
