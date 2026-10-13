package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: a write the quarantine stops comes back as an ordinary "nothing changed", with no reason, so the adapter
 * cannot tell a stack with nothing to do from one it must not touch. Violates Law T14 (typed write outcomes).
 */
public final class SilentNoOpCarrier extends DelegatingCarrier {
    public SilentNoOpCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    private static WriteOutcome silence(WriteOutcome outcome) {
        return outcome instanceof WriteOutcome.Refused r ? new WriteOutcome.Written(r.root(), false) : outcome;
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        return silence(super.write(root, snapshot, mode));
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return silence(super.set(root, def, value, mode));
    }

    @Override
    public WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def, OnQuarantine mode) {
        return silence(super.clear(root, def, mode));
    }
}
