package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken: a record with a malformed or missing field decodes as the default value instead of throwing
 * {@link CodecException}, so the corruption is neither rejected nor quarantined and a re-save destroys it.
 * Violates T12 (a malformed field is a CodecException).
 */
public final class ForgivingRecords extends DeviantRecords {
    @Override
    Object decode(RecordCodec<Object> real, TagNode node, Object fallback) {
        try {
            return real.decode(node);
        } catch (CodecException e) {
            return fallback;
        }
    }

    @Override
    FieldAwareCodec.DecodedResult<Object> decodeFieldAware(RecordCodec<Object> real, TagNode.TagCompound raw,
                                                           Object fallback) {
        try {
            return real.decodeFieldAware(raw);
        } catch (CodecException e) {
            return new FieldAwareCodec.DecodedResult<>(fallback, raw, Collections.<String>emptySet());
        }
    }
}
