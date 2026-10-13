package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: on read, every registered presence component that is not stored comes back present, as if absence
 * were a default of "there". Violates Law T13 (absence is absence).
 */
public final class MaterializingMarksTranslator implements RosettaTranslator {
    private final RosettaTranslator delegate = ReferenceTranslator.create();

    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        return delegate.toLegacyNbt(snapshot, registry);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        CarrierSnapshot read = delegate.fromLegacyNbt(nbt, registry);
        for (ComponentDefinition<?> def : registry.definitions()) {
            if (def.isPresence() && !read.has(def.id()) && !read.isRemoved(def.id())) {
                read = read.withMark((ComponentDefinition<Unit>) def); // broken: absent becomes present
            }
        }
        return read;
    }
}
