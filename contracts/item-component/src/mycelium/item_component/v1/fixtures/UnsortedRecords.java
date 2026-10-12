package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken: a record's fields are written in reverse key order, so equal records need not serialize to equal bytes.
 * Violates T12 (record fields are written in sorted order, T6).
 */
public final class UnsortedRecords extends DeviantRecords {
    private static TagNode.TagCompound reverse(TagNode.TagCompound sorted) {
        Map<String, TagNode> out = new TreeMap<>(Comparator.<String>reverseOrder());
        out.putAll(sorted.entries());
        return new TagNode.TagCompound(out);
    }

    @Override
    TagNode encode(RecordCodec<Object> real, Object value) {
        return reverse((TagNode.TagCompound) real.encode(value));
    }

    @Override
    TagNode.TagCompound updateFieldAware(RecordCodec<Object> real, TagNode.TagCompound original, Object value)
            throws CodecException {
        return reverse(real.updateFieldAware(original, value));
    }
}
