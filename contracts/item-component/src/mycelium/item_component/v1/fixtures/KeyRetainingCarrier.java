package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: a write stores the migrated component in the fence and leaves the old keys where they were, so the
 * state is written twice and the two can drift. Violates Law T17 (writing after a migrated read strips the old keys).
 */
public final class KeyRetainingCarrier extends DelegatingCarrier {
    public KeyRetainingCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    private TagNode.TagCompound restore(TagNode.TagCompound from, TagNode.TagCompound result) {
        TagNode.TagCompound out = result;
        for (LegacyKeyMigration.ComponentRule<?> rule : migration().rules()) {
            for (LegacyKeyMigration.Key key : rule.keys()) {
                TagNode old = key.path().size() == 1 ? from.get(key.path().get(0)) : null;
                if (old != null) {
                    out = out.with(key.path().get(0), old);
                }
            }
        }
        return out;
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        WriteOutcome outcome = super.write(root, snapshot, mode);
        if (!(outcome instanceof WriteOutcome.Written w)) {
            return outcome;
        }
        TagNode.TagCompound out = restore(root, w.root());
        return new WriteOutcome.Written(out, !out.equals(root));
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return write(root, read(root).with(def, value), mode);
    }
}
