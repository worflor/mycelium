package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: whatever the mode says, a write goes through and discards the unreadable data it meets, and reports
 * success. Violates Law T14 (the anti-data-loss guarantee holds unless the adapter asks to replace).
 */
public final class QuarantineDestroyingCarrier extends DelegatingCarrier {
    public QuarantineDestroyingCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        return super.write(root, snapshot, OnQuarantine.REPLACE);
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return super.set(root, def, value, OnQuarantine.REPLACE);
    }

    @Override
    public WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def, OnQuarantine mode) {
        return super.clear(root, def, OnQuarantine.REPLACE);
    }
}
