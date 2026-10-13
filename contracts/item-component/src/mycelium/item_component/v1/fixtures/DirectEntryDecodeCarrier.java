package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: reading one component decodes its fenced entry directly instead of going through the snapshot, the way
 * an adapter's own {@code get} does. It agrees on a clean stack and parts from the snapshot on migrated old keys, an
 * unreadable envelope and an explicit removal. Violates Law T15 (one decode path).
 */
public final class DirectEntryDecodeCarrier extends DelegatingCarrier {
    public DirectEntryDecodeCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    @Override
    public <T> ComponentRead<T> read(TagNode.TagCompound root, ComponentDefinition<T> def) {
        if (root.get(RosettaTranslator.FENCED_NAMESPACE) instanceof TagNode.TagCompound fenced) {
            TagNode entry = fenced.get(def.id().toString());
            if (entry != null) {
                try {
                    return new ComponentRead.Present<>(def.decodeValue(entry));
                } catch (CodecException | RuntimeException e) {
                    return new ComponentRead.Quarantined<>(RecoveryRecord.of(def.id().toString(), entry,
                            RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD, def.id(), String.valueOf(e.getMessage())));
                }
            }
        }
        return new ComponentRead.Absent<>();
    }
}
