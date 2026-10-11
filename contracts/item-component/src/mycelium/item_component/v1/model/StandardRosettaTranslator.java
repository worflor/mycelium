package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Standard pure reference implementation of {@link RosettaTranslator}.
 * Ships in the model package so downstream consumers (Cartomancy, Somnium)
 * receive a complete, functional translation engine upon {@code myceliumImport}.
 */
public final class StandardRosettaTranslator implements RosettaTranslator {
    public static final StandardRosettaTranslator INSTANCE = new StandardRosettaTranslator();

    public static StandardRosettaTranslator instance() {
        return INSTANCE;
    }

    public StandardRosettaTranslator() {
    }

    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(registry, "registry");

        // Preserved unreadable non-compound envelope
        if (snapshot.components().isEmpty() && snapshot.removals().isEmpty() && snapshot.unmappedTags().containsKey("!unreadable_envelope")) {
            return TagNode.TagCompound.empty().with(FENCED_NAMESPACE, snapshot.unmappedTags().get("!unreadable_envelope"));
        }

        Map<String, TagNode> componentTags = new TreeMap<>();

        // 1. Populate unmapped foreign and quarantined tags (Law T4 & Anti-Data Loss)
        for (Map.Entry<String, TagNode> entry : snapshot.unmappedTags().entrySet()) {
            if (!entry.getKey().equals("!unreadable_envelope")) {
                componentTags.put(entry.getKey(), entry.getValue());
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

        // 3. Encode typed components (Law T1, Law T2 default elision)
        // Valid components override any stale quarantined tag for the same key.
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
                } else {
                    // Law T2: Default elision - elide from NBT
                    componentTags.remove(id.toString());
                }
            } else {
                // Component absent from supplied registry: preserve as raw TagNode or string
                if (value instanceof TagNode tn) {
                    componentTags.put(id.toString(), tn);
                } else {
                    componentTags.put(id.toString(), TagNode.TagString.of(String.valueOf(value)));
                }
            }
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
        TagNode fencedNode = nbt.get(FENCED_NAMESPACE);
        if (fencedNode == null) {
            return CarrierSnapshot.empty();
        }
        if (!(fencedNode instanceof TagNode.TagCompound)) {
            // Unreadable non-compound envelope: preserve without crashing or losing data
            Map<String, TagNode> unmapped = Collections.singletonMap("!unreadable_envelope", fencedNode);
            return new CarrierSnapshot(Collections.emptyMap(), unmapped, Collections.emptySet());
        }

        TagNode.TagCompound fenced = (TagNode.TagCompound) fencedNode;
        Map<ComponentId, Object> components = new TreeMap<>();
        Map<String, TagNode> unmapped = new TreeMap<>();
        Set<ComponentId> removals = new TreeSet<>();

        // Decode negative patches
        TagNode removedNode = fenced.get("!removed");
        if (removedNode instanceof TagNode.TagList remList) {
            List<TagNode> invalidElements = new ArrayList<>();
            for (int i = 0; i < remList.size(); i++) {
                TagNode elem = remList.get(i);
                if (elem instanceof TagNode.TagString ts) {
                    try {
                        removals.add(ComponentId.of(ts.value()));
                    } catch (IllegalArgumentException e) {
                        invalidElements.add(elem);
                    }
                } else {
                    invalidElements.add(elem);
                }
            }
            if (!invalidElements.isEmpty()) {
                unmapped.put("!removed_quarantine", new TagNode.TagList(invalidElements));
            }
        } else if (removedNode != null) {
            // Anti-Data Loss Quarantine: preserve malformed scalar/non-list !removed under distinct key
            unmapped.put("!removed_quarantine", removedNode);
        }

        Map<ComponentId, List<Map.Entry<String, TagNode>>> canonicalGroups = new LinkedHashMap<>();

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

            canonicalGroups.computeIfAbsent(id, k -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<ComponentId, List<Map.Entry<String, TagNode>>> group : canonicalGroups.entrySet()) {
            ComponentId id = group.getKey();
            List<Map.Entry<String, TagNode>> rawEntries = group.getValue();

            if (rawEntries.size() > 1) {
                // Contradictory external state / alias collision (e.g. "damage" and "minecraft:damage")
                // Deterministic conflict: retain all original candidate tags in quarantine
                for (Map.Entry<String, TagNode> raw : rawEntries) {
                    unmapped.put(raw.getKey(), raw.getValue());
                }
                continue;
            }

            Map.Entry<String, TagNode> single = rawEntries.get(0);
            String key = single.getKey();
            TagNode node = single.getValue();

            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                ComponentDefinition<?> def = defOpt.get();
                try {
                    Object decoded = def.decodeValue(node);
                    components.put(id, decoded);
                    // Mutual exclusivity: a valid decoded value supersedes any simultaneous negative removal
                    removals.remove(id);
                } catch (Exception e) {
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
