package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Field-aware codec / lens abstraction that tracks consumed field footprints
 * and preserves unconsumed remainder tags across decode-edit-encode cycles (Law of Field-Aware Preservation).
 */
public interface FieldAwareCodec<T> {

    record DecodedResult<T>(
            T value,
            TagNode.TagCompound remainder,
            Set<String> consumedFootprint
    ) {
        public DecodedResult {
            Objects.requireNonNull(remainder, "remainder");
            consumedFootprint = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(consumedFootprint, "consumedFootprint")));
        }
    }

    DecodedResult<T> decodeFieldAware(TagNode.TagCompound raw) throws CodecException;

    TagNode.TagCompound updateFieldAware(TagNode.TagCompound originalRaw, T newValue) throws CodecException;

    static <V> FieldAwareCodec<V> ofCompoundField(String fieldName, ComponentCodec<V> valueCodec) {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(valueCodec, "valueCodec");

        return new FieldAwareCodec<V>() {
            @Override
            public DecodedResult<V> decodeFieldAware(TagNode.TagCompound raw) throws CodecException {
                Objects.requireNonNull(raw, "raw");
                TagNode fieldNode = raw.get(fieldName);
                if (fieldNode == null) {
                    throw new CodecException("Missing expected field '" + fieldName + "' in raw compound");
                }
                V val = valueCodec.decode(fieldNode);

                Map<String, TagNode> remainderEntries = new LinkedHashMap<>();
                for (Map.Entry<String, TagNode> e : raw.entries().entrySet()) {
                    if (!e.getKey().equals(fieldName)) {
                        remainderEntries.put(e.getKey(), e.getValue());
                    }
                }
                TagNode.TagCompound remainder = new TagNode.TagCompound(remainderEntries);
                Set<String> footprint = Collections.singleton(fieldName);
                return new DecodedResult<>(val, remainder, footprint);
            }

            @Override
            public TagNode.TagCompound updateFieldAware(TagNode.TagCompound originalRaw, V newValue) throws CodecException {
                Objects.requireNonNull(originalRaw, "originalRaw");
                Map<String, TagNode> updatedEntries = new LinkedHashMap<>(originalRaw.entries());
                updatedEntries.put(fieldName, valueCodec.encode(newValue));
                return new TagNode.TagCompound(updatedEntries);
            }
        };
    }
}
