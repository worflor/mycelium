package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Fixture delegate for {@link StandardRosettaTranslator} used by test runners.
 */
public final class ReferenceTranslator implements RosettaTranslator {
    private final RosettaTranslator delegate = StandardRosettaTranslator.INSTANCE;

    public static ReferenceTranslator create() {
        return new ReferenceTranslator();
    }

    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        return delegate.toLegacyNbt(snapshot, registry);
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        return delegate.fromLegacyNbt(nbt, registry);
    }

    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry, ItemPrototype prototype) {
        return delegate.toLegacyNbt(snapshot, registry, prototype);
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry, ItemPrototype prototype) {
        return delegate.fromLegacyNbt(nbt, registry, prototype);
    }

    @Override
    public TranslationPlan compilePlan(ComponentRegistry registry, ItemPrototype prototype) {
        return delegate.compilePlan(registry, prototype);
    }
}
