package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken fixture: writes component tags directly to the root of the legacy NBT compound.
 * Violates Law T3 (Namespace Fencing).
 */
public final class RootPollutingTranslator implements RosettaTranslator {
    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        Map<String, TagNode> rootTags = new TreeMap<>();
        for (Map.Entry<ComponentId, Object> entry : snapshot.components().entrySet()) {
            ComponentId id = entry.getKey();
            Object value = entry.getValue();
            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();
                if (!def.isDefault(value)) {
                    // Broken: polluting root NBT namespace instead of fencing under mycelium$c
                    rootTags.put(id.toString(), def.encodeValue(value));
                }
            }
        }
        return new TagNode.TagCompound(rootTags);
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        Objects.requireNonNull(nbt, "nbt");
        Objects.requireNonNull(registry, "registry");

        Map<ComponentId, Object> components = new TreeMap<>();
        for (Map.Entry<String, TagNode> entry : nbt.entries().entrySet()) {
            String key = entry.getKey();
            TagNode node = entry.getValue();

            ComponentId id;
            try {
                id = ComponentId.of(key);
            } catch (IllegalArgumentException e) {
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
        }
        return new CarrierSnapshot(components, Collections.emptyMap());
    }
}

