package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken fixture: serializes components even when their value equals the registered default value.
 * Violates Law T2 (Default Elision).
 */
public final class DefaultWritingTranslator implements RosettaTranslator {
    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        Map<String, TagNode> componentTags = new TreeMap<>();
        for (Map.Entry<ComponentId, Object> entry : snapshot.components().entrySet()) {
            ComponentId id = entry.getKey();
            Object value = entry.getValue();
            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();
                // Broken: always serializes, violating Law T2 Default Elision
                componentTags.put(id.toString(), def.encodeValue(value));
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
        return TagNode.TagCompound.empty().with(FENCED_NAMESPACE, new TagNode.TagCompound(componentTags));
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        return ReferenceTranslator.create().fromLegacyNbt(nbt, registry);
    }
}
