package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: a component that is stored but unreadable reads as absent (the adapter's "null"), so nothing tells the
 * caller the stack holds data it could not read. Violates Law T15 (quarantine is visible to the single read).
 */
public final class QuietReadCarrier extends DelegatingCarrier {
    public QuietReadCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    @Override
    public <T> ComponentRead<T> read(TagNode.TagCompound root, ComponentDefinition<T> def) {
        ComponentRead<T> read = super.read(root, def);
        return read.isQuarantined() ? new ComponentRead.Absent<>() : read;
    }
}
