package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: after a write every old key is stripped, readable or not, the way a naive "remove the legacy keys"
 * does, so an old value that did not read is gone. Violates Law T19 (an unreadable old key is quarantined, not dropped).
 */
public final class DroppingMigrationCarrier extends DelegatingCarrier {
    public DroppingMigrationCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        WriteOutcome outcome = super.write(root, snapshot, mode);
        if (!(outcome instanceof WriteOutcome.Written w)) {
            return outcome;
        }
        TagNode.TagCompound out = w.root();
        for (LegacyKeyMigration.ComponentRule<?> rule : migration().rules()) {
            for (LegacyKeyMigration.Key key : rule.keys()) {
                out = key.path().size() == 1 ? out.without(key.path().get(0)) : out;
            }
        }
        // the quarantine copies go with them
        for (String k : new java.util.ArrayList<>(out.entries().keySet())) {
            if (k.equals(RosettaTranslator.FENCED_NAMESPACE) && out.get(k) instanceof TagNode.TagCompound fenced) {
                TagNode.TagCompound cleaned = fenced;
                for (String inner : fenced.entries().keySet()) {
                    if (inner.startsWith(LegacyKeyMigration.QUARANTINE_PREFIX)) {
                        cleaned = cleaned.without(inner);
                    }
                }
                out = cleaned.isEmpty() ? out.without(k) : out.with(k, cleaned);
            }
        }
        return new WriteOutcome.Written(out, !out.equals(root));
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return write(root, read(root).with(def, value), mode);
    }
}
