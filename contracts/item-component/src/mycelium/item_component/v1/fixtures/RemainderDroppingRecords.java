package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken: the fields of a record that the codec does not know are dropped when the record is edited and written back.
 * Violates T12 (a record keeps its remainder, T9).
 */
public final class RemainderDroppingRecords extends DeviantRecords {
    @Override
    TagNode.TagCompound updateFieldAware(RecordCodec<Object> real, TagNode.TagCompound original, Object value)
            throws CodecException {
        return real.updateFieldAware(TagNode.TagCompound.empty(), value);
    }
}
