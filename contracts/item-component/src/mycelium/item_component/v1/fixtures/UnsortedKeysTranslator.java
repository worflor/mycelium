package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken fixture: serializes keys in reverse order.
 * Violates Law T6 (Deterministic Key Ordering).
 */
public final class UnsortedKeysTranslator implements RosettaTranslator {
    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(registry, "registry");

        // Use reverse-order map to violate deterministic ordering (Law T6)
        Map<String, TagNode> componentTags = new TreeMap<>(Comparator.reverseOrder());

        for (Map.Entry<ComponentId, Object> entry : snapshot.components().entrySet()) {
            ComponentId id = entry.getKey();
            Object value = entry.getValue();

            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();
                if (!def.isDefault(value)) {
                    TagNode node = def.encodeValue(value);
                    componentTags.put(id.toString(), node);
                }
            }
        }

        if (!snapshot.removals().isEmpty()) {
            List<TagNode> removalNodes = new ArrayList<>();
            for (ComponentId rem : snapshot.removals()) {
                removalNodes.add(TagNode.TagString.of(rem.toString()));
            }
            componentTags.put("!removed", new TagNode.TagList(removalNodes));
        }

        for (Map.Entry<String, TagNode> entry : snapshot.unmappedTags().entrySet()) {
            componentTags.put(entry.getKey(), entry.getValue());
        }

        if (componentTags.isEmpty()) {
            return TagNode.TagCompound.empty();
        }

        // Broken: entries are in reverse order
        return TagNode.TagCompound.empty().with(FENCED_NAMESPACE, new TagNode.TagCompound(componentTags));
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        return ReferenceTranslator.create().fromLegacyNbt(nbt, registry);
    }
}
