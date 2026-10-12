package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken: an absent optional field is written anyway, as an empty compound, so "absent" and "present but empty" differ
 * on disk. Violates T12 (an absent optional is not written).
 */
public final class AbsentWritingRecords extends DeviantRecords {
    private static TagNode.TagCompound pad(RecordCodec<Object> real, TagNode.TagCompound written) {
        Map<String, TagNode> out = new TreeMap<>(written.entries());
        for (String name : real.fieldNames()) {
            if (!out.containsKey(name)) {
                out.put(name, TagNode.TagCompound.empty());
            }
        }
        return new TagNode.TagCompound(out);
    }

    @Override
    TagNode encode(RecordCodec<Object> real, Object value) {
        return pad(real, (TagNode.TagCompound) real.encode(value));
    }

    @Override
    TagNode.TagCompound updateFieldAware(RecordCodec<Object> real, TagNode.TagCompound original, Object value)
            throws CodecException {
        return pad(real, real.updateFieldAware(original, value));
    }
}
