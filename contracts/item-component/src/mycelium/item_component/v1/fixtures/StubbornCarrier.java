package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: {@code REPLACE} is read as {@code REFUSE}, so an adapter that decided to discard an unreadable entry
 * (a corrupt value it will never repair) can never write over it. Violates Law T14 (the adapter decides).
 */
public final class StubbornCarrier extends DelegatingCarrier {
    public StubbornCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    private static OnQuarantine stubborn(OnQuarantine mode) {
        return mode == OnQuarantine.REPLACE ? OnQuarantine.REFUSE : mode;
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        return super.write(root, snapshot, stubborn(mode));
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return super.set(root, def, value, stubborn(mode));
    }

    @Override
    public WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def, OnQuarantine mode) {
        return super.clear(root, def, stubborn(mode));
    }
}
