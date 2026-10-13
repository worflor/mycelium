package mycelium.item_presentation.v1.model;

import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_component.v1.model.ComponentDefinition;
import mycelium.item_component.v1.model.ComponentId;
import mycelium.item_component.v1.model.ComponentRegistry;
import mycelium.item_component.v1.model.TagNode;

import java.util.Optional;

/**
 * A stack's {@code item.component@1} snapshot, read as presentation sees it. A component the snapshot does not carry
 * reads as its registered default, as the carrier elides defaults; a component removed on the stack reads as absent.
 * A field is read from the component's encoded record by its dotted path, and a leaf is given the kind the catalog
 * says it has (a byte is a flag or a number only the catalog can tell).
 */
public final class CarrierView implements ComponentView {
    private final CarrierSnapshot snapshot;
    private final ComponentRegistry registry;
    private final ComponentCatalog catalog;

    public CarrierView(CarrierSnapshot snapshot, ComponentRegistry registry, ComponentCatalog catalog) {
        this.snapshot = snapshot;
        this.registry = registry;
        this.catalog = catalog;
    }

    /** Whether the snapshot carries the component (a unit component's presence). */
    @Override
    public boolean has(String componentId) {
        ComponentId id = ComponentId.of(componentId);
        return snapshot.has(id) && !snapshot.isRemoved(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Object> field(String componentId, String fieldPath) {
        ComponentId id = ComponentId.of(componentId);
        Optional<ComponentDefinition<?>> found = registry.get(id);
        ComponentCatalog.Component component = catalog.component(componentId);
        if (!found.isPresent() || component == null) {
            return Optional.empty();
        }
        ComponentDefinition<Object> definition = (ComponentDefinition<Object>) found.get();
        Object value = snapshot.get(definition);
        if (value == null) {
            return Optional.empty();
        }
        TagNode node = definition.encodeValue(value);
        for (String step : fieldPath.isEmpty() ? new String[0] : fieldPath.split("\\.")) {
            if (!(node instanceof TagNode.TagCompound) || !((TagNode.TagCompound) node).has(step)) {
                return Optional.empty();
            }
            node = ((TagNode.TagCompound) node).get(step);
        }
        FieldKind kind = fieldPath.isEmpty() ? component.scalar() : component.fields().get(fieldPath);
        if (kind == null) {
            return Optional.empty();
        }
        switch (kind) {
            case BOOL:
                return node instanceof TagNode.TagByte ? Optional.<Object>of(Boolean.valueOf(((TagNode.TagByte) node).value() != 0)) : Optional.empty();
            case TEXT:
                return node instanceof TagNode.TagString ? Optional.<Object>of(((TagNode.TagString) node).value()) : Optional.empty();
            default:
                if (node instanceof TagNode.TagByte) {
                    return Optional.<Object>of(Long.valueOf(((TagNode.TagByte) node).value()));
                }
                if (node instanceof TagNode.TagShort) {
                    return Optional.<Object>of(Long.valueOf(((TagNode.TagShort) node).value()));
                }
                if (node instanceof TagNode.TagInt) {
                    return Optional.<Object>of(Long.valueOf(((TagNode.TagInt) node).value()));
                }
                if (node instanceof TagNode.TagLong) {
                    return Optional.<Object>of(Long.valueOf(((TagNode.TagLong) node).value()));
                }
                return Optional.empty();
        }
    }
}
