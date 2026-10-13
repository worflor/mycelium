package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/** A carrier that forwards everything to the model's own; a broken fixture extends it and overrides the one thing it gets wrong. */
abstract class DelegatingCarrier implements FencedCarrier {
    protected final FencedCarrier delegate;

    DelegatingCarrier(FencedCarrier delegate) {
        this.delegate = delegate;
    }

    DelegatingCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        this(StandardFencedCarrier.of(registry, migration));
    }

    @Override
    public ComponentRegistry registry() {
        return delegate.registry();
    }

    @Override
    public LegacyKeyMigration migration() {
        return delegate.migration();
    }

    @Override
    public CarrierSnapshot read(TagNode.TagCompound root) {
        return delegate.read(root);
    }

    @Override
    public <T> ComponentRead<T> read(TagNode.TagCompound root, ComponentDefinition<T> def) {
        return delegate.read(root, def);
    }

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        return delegate.write(root, snapshot, mode);
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return delegate.set(root, def, value, mode);
    }

    @Override
    public WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def, OnQuarantine mode) {
        return delegate.clear(root, def, mode);
    }
}
