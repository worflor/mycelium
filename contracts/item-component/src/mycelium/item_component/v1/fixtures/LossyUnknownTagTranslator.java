package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken fixture: silently drops unrecognized tags under the fenced namespace during decode.
 * Violates Law T4 (Foreign Tag Preservation).
 */
public final class LossyUnknownTagTranslator implements RosettaTranslator {
    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        return ReferenceTranslator.create().toLegacyNbt(snapshot, registry);
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        Optional<TagNode.TagCompound> fencedOpt = nbt.getCompound(FENCED_NAMESPACE);
        if (!fencedOpt.isPresent()) {
            return CarrierSnapshot.empty();
        }

        TagNode.TagCompound fenced = fencedOpt.get();
        Map<ComponentId, Object> components = new TreeMap<>();

        for (Map.Entry<String, TagNode> entry : fenced.entries().entrySet()) {
            String key = entry.getKey();
            TagNode node = entry.getValue();

            ComponentId id;
            try {
                id = ComponentId.of(key);
            } catch (IllegalArgumentException e) {
                // Broken: drops malformed or unrecognized tags completely
                continue;
            }

            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                ComponentDefinition<?> def = defOpt.get();
                try {
                    components.put(id, def.decodeValue(node));
                } catch (CodecException ignored) {
                }
            }
            // Broken: deliberately drops unknown tags (empty unmapped map)
        }

        return new CarrierSnapshot(components, Collections.emptyMap());
    }
}
