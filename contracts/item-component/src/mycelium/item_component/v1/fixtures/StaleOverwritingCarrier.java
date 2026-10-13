package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.Map;

/**
 * Broken fixture: an old key present beside a fenced value wins on every read, so a stack that already moved on is pulled
 * back to the value it had before, and a read after a write is not the read before it. Violates Law T18 (migration is
 * idempotent: the fenced state is authoritative).
 */
public final class StaleOverwritingCarrier extends DelegatingCarrier {
    public StaleOverwritingCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, migration);
    }

    @Override
    public CarrierSnapshot read(TagNode.TagCompound root) {
        CarrierSnapshot snapshot = super.read(root);
        for (LegacyKeyMigration.ComponentRule<?> rule : migration().rules()) {
            Map<LegacyKeyMigration.Key, TagNode> found = rule.found(root);
            if (!found.isEmpty()) {
                try {
                    snapshot = snapshot.with(rule.target().id(), rule.apply(found));
                } catch (CodecException | RuntimeException ignored) {
                    // left to the standard quarantine
                }
            }
        }
        return snapshot;
    }

    @Override
    public <T> ComponentRead<T> read(TagNode.TagCompound root, ComponentDefinition<T> def) {
        CarrierSnapshot snapshot = read(root);
        return snapshot.has(def) ? new ComponentRead.Present<>(snapshot.get(def)) : super.read(root, def);
    }
}
