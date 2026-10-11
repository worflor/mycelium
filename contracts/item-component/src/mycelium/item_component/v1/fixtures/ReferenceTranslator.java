package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Reference implementation of {@link RosettaTranslator} keeping all laws T1 through T6.
 */
public final class ReferenceTranslator implements RosettaTranslator {

    public static ReferenceTranslator create() {
        return new ReferenceTranslator();
    }

    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(registry, "registry");

        Map<String, TagNode> componentTags = new TreeMap<>();

        // 1. Encode typed components
        for (Map.Entry<ComponentId, Object> entry : snapshot.components().entrySet()) {
            ComponentId id = entry.getKey();
            Object value = entry.getValue();

            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();
                // Law T2: Default elision - do not serialize if matching default
                if (!def.isDefault(value)) {
                    TagNode node = def.encodeValue(value);
                    componentTags.put(id.toString(), node);
                }
            }
        }

        // 2. Encode explicit removals (negative patches)
        if (!snapshot.removals().isEmpty()) {
            List<TagNode> removalNodes = new ArrayList<>();
            for (ComponentId rem : snapshot.removals()) {
                removalNodes.add(TagNode.TagString.of(rem.toString()));
            }
            componentTags.put("!removed", new TagNode.TagList(removalNodes));
        }

        // 3. Preserve unmapped foreign and quarantined tags (Law T4 & Anti-Data Loss)
        for (Map.Entry<String, TagNode> entry : snapshot.unmappedTags().entrySet()) {
            componentTags.put(entry.getKey(), entry.getValue());
        }

        // Law T2: Default elision - if no components, removals, or unmapped tags survive, omit fenced compound
        if (componentTags.isEmpty()) {
            return TagNode.TagCompound.empty();
        }

        // Law T3: Namespace fencing - nest strictly inside mycelium$c
        // Law T6: Sorted keys via TreeMap in TagCompound constructor
        return TagNode.TagCompound.empty().with(FENCED_NAMESPACE, new TagNode.TagCompound(componentTags));
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        Objects.requireNonNull(nbt, "nbt");
        Objects.requireNonNull(registry, "registry");

        // Law T3: Only inspect fenced compound
        Optional<TagNode.TagCompound> fencedOpt = nbt.getCompound(FENCED_NAMESPACE);
        if (!fencedOpt.isPresent()) {
            return CarrierSnapshot.empty();
        }

        TagNode.TagCompound fenced = fencedOpt.get();
        Map<ComponentId, Object> components = new TreeMap<>();
        Map<String, TagNode> unmapped = new TreeMap<>();
        Set<ComponentId> removals = new TreeSet<>();

        // Decode negative patches
        TagNode removedNode = fenced.get("!removed");
        if (removedNode instanceof TagNode.TagList remList) {
            for (int i = 0; i < remList.size(); i++) {
                TagNode elem = remList.get(i);
                if (elem instanceof TagNode.TagString ts) {
                    try {
                        removals.add(ComponentId.of(ts.value()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        for (Map.Entry<String, TagNode> entry : fenced.entries().entrySet()) {
            String key = entry.getKey();
            if (key.equals("!removed")) continue;

            TagNode node = entry.getValue();

            ComponentId id;
            try {
                id = ComponentId.of(key);
            } catch (IllegalArgumentException e) {
                // Malformed ID: treat as unmapped tag
                unmapped.put(key, node);
                continue;
            }

            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                ComponentDefinition<?> def = defOpt.get();
                try {
                    Object decoded = def.decodeValue(node);
                    components.put(id, decoded);
                } catch (CodecException e) {
                    // Law T5: Diagnostic rejection on corruption - does not throw unchecked exception
                    // Anti-Data Loss Quarantine: Preserve corrupt tag in unmapped tags so re-saving
                    // does not permanently destroy user data
                    unmapped.put(key, node);
                }
            } else {
                // Law T4: Foreign tag preservation - keep unknown tags
                unmapped.put(key, node);
            }
        }

        return new CarrierSnapshot(components, unmapped, removals);
    }
}
