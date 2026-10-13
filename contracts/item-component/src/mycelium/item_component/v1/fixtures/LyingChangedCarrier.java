package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: every write says it changed the root, even one that left it as it was, so an adapter cannot skip the
 * platform write. Violates Law T14 (a Written outcome's {@code changed} is true exactly when the root differs).
 */
public final class LyingChangedCarrier extends DelegatingCarrier {
    public LyingChangedCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    private static WriteOutcome lie(WriteOutcome outcome) {
        return outcome instanceof WriteOutcome.Written w ? new WriteOutcome.Written(w.root(), true) : outcome;
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        return lie(super.write(root, snapshot, mode));
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return lie(super.set(root, def, value, mode));
    }

    @Override
    public WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def, OnQuarantine mode) {
        return lie(super.clear(root, def, mode));
    }
}
