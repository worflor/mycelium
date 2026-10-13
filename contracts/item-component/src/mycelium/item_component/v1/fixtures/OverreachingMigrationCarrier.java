package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Broken fixture: a write strips every root key that starts like an old key (its prefix before the first underscore), so a
 * look-alike key, or another mod's, goes with them. Violates Law T20 (keys the table does not name are untouched).
 */
public final class OverreachingMigrationCarrier extends DelegatingCarrier {
    public OverreachingMigrationCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        WriteOutcome outcome = super.write(root, snapshot, mode);
        if (!(outcome instanceof WriteOutcome.Written w)) {
            return outcome;
        }
        List<String> prefixes = new ArrayList<>();
        for (LegacyKeyMigration.ComponentRule<?> rule : migration().rules()) {
            for (LegacyKeyMigration.Key key : rule.keys()) {
                String first = key.path().get(0);
                prefixes.add(first.contains("_") ? first.substring(0, first.indexOf('_')) : first);
            }
        }
        TagNode.TagCompound out = w.root();
        for (String key : new ArrayList<>(out.entries().keySet())) {
            for (String prefix : prefixes) {
                if (key.startsWith(prefix) && !key.equals(RosettaTranslator.FENCED_NAMESPACE)) {
                    out = out.without(key);
                }
            }
        }
        return new WriteOutcome.Written(out, !out.equals(root));
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return write(root, read(root).with(def, value), mode);
    }
}
