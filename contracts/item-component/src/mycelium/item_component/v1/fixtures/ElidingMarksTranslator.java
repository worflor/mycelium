package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: treats a component with no default as elidable, so a mark (or a zero that means "zero") is never
 * written and a stack loses it on the first save. Violates Law T13 (Presence Components).
 */
public final class ElidingMarksTranslator implements RosettaTranslator {
    private final RosettaTranslator delegate = ReferenceTranslator.create();

    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        CarrierSnapshot kept = snapshot;
        for (ComponentId id : snapshot.components().keySet()) {
            if (registry.get(id).map(d -> !d.hasDefault()).orElse(false)) {
                kept = kept.without(id); // broken: "no default" read as "nothing to write"
            }
        }
        return delegate.toLegacyNbt(kept, registry);
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        return delegate.fromLegacyNbt(nbt, registry);
    }
}
