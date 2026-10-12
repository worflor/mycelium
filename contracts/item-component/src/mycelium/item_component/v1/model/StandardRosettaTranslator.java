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
        return toLegacyNbt(snapshot, registry, ItemPrototype.empty());
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        return fromLegacyNbt(nbt, registry, ItemPrototype.empty());
    }

    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry, ItemPrototype prototype) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(prototype, "prototype");

        // Preserved unreadable non-compound envelope with no edits
        if (snapshot.components().isEmpty() && snapshot.removals().isEmpty() && snapshot.unmappedTags().isEmpty() && snapshot.rawEnvelope().isPresent()) {
            return TagNode.TagCompound.empty().with(FENCED_NAMESPACE, snapshot.rawEnvelope().get());
        }

        Map<String, TagNode> componentTags = new TreeMap<>();

        // 1. Populate unmapped foreign and legacy quarantined tags (Law T4 & Anti-Data Loss)
        for (Map.Entry<String, TagNode> entry : snapshot.unmappedTags().entrySet()) {
            componentTags.put(entry.getKey(), entry.getValue());
        }

        // 2. Encode explicit removals (negative patches)
        if (!snapshot.removals().isEmpty()) {
            List<TagNode> removalNodes = new ArrayList<>();
            for (ComponentId rem : snapshot.removals()) {
                removalNodes.add(TagNode.TagString.of(rem.toString()));
            }
            componentTags.put("!removed", new TagNode.TagList(removalNodes));
        }

        // 3. Encode structured recovery records
        if (!snapshot.recoveryRecords().isEmpty() || snapshot.rawEnvelope().isPresent()) {
            Map<String, TagNode> recoveryCompound = new TreeMap<>();
            recoveryCompound.put("version", TagNode.TagInt.of(1));

            if (snapshot.rawEnvelope().isPresent()) {
                recoveryCompound.put("raw_envelope", snapshot.rawEnvelope().get());
            }

            if (!snapshot.recoveryRecords().isEmpty()) {
                List<TagNode> recordList = new ArrayList<>(snapshot.recoveryRecords().size());
                for (RecoveryRecord rec : snapshot.recoveryRecords()) {
                    Map<String, TagNode> recEntries = new TreeMap<>();
                    recEntries.put("loc", TagNode.TagString.of(rec.location()));
                    recEntries.put("raw", rec.rawNode());
                    recEntries.put("cat", TagNode.TagString.of(rec.category().name()));
                    recEntries.put("ver", TagNode.TagInt.of(rec.schemaVersion()));
                    recEntries.put("diag", TagNode.TagString.of(rec.diagnosticMessage()));
                    rec.componentId().ifPresent(id -> recEntries.put("id", TagNode.TagString.of(id.toString())));
                    recordList.add(new TagNode.TagCompound(recEntries));
                }
                recoveryCompound.put("records", new TagNode.TagList(recordList));
            }
            componentTags.put("!recovery", new TagNode.TagCompound(recoveryCompound));
        }

        // 4. Encode typed components (Law T1, Law T2 & T7 default elision)
        for (Map.Entry<ComponentId, Object> entry : snapshot.components().entrySet()) {
            ComponentId id = entry.getKey();
            Object value = entry.getValue();

            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();

                // Elision against prototype baseline
                Optional<Object> protoBase = prototype.getBaseComponent(id);
                if (protoBase.isPresent()) {
                    if (ValueSemantics.deepEquals(protoBase.get(), value)) {
                        componentTags.remove(id.toString());
                        continue;
                    }
                } else if (prototype.isEmpty()) {
                    // Law T2: Default elision when no prototype baseline exists
                    if (def.isDefault(value)) {
                        componentTags.remove(id.toString());
                        continue;
                    }
                }

                TagNode node;
                if (def.fieldAwareCodec().isPresent() && snapshot.retainedResiduals().containsKey(id)) {
                    try {
                        @SuppressWarnings("unchecked")
                        FieldAwareCodec<Object> fac = (FieldAwareCodec<Object>) def.fieldAwareCodec().get();
                        node = fac.updateFieldAware(snapshot.retainedResiduals().get(id), value);
                    } catch (CodecException e) {
                        node = def.encodeValue(value);
                    }
                } else {
                    node = def.encodeValue(value);
                }
                componentTags.put(id.toString(), node);
            } else if (value instanceof TagNode tn) {
                // Component absent from registry but preserved as raw TagNode
                componentTags.put(id.toString(), tn);
            } else {
                // Unregistered arbitrary Java object without a codec or TagNode transport representation
                throw new IllegalArgumentException("Unregistered component " + id + " of type " + value.getClass().getName()
                        + " cannot be serialized without a registered codec or TagNode representation");
            }
        }

        // Law T2: Default elision - if no components, removals, recovery, or unmapped tags survive, omit fenced compound
        if (componentTags.isEmpty()) {
            return TagNode.TagCompound.empty();
        }

        // Law T3: Namespace fencing - nest strictly inside mycelium$c
        // Law T6: Sorted keys via TreeMap in TagCompound constructor
        return TagNode.TagCompound.empty().with(FENCED_NAMESPACE, new TagNode.TagCompound(componentTags));
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry, ItemPrototype prototype) {
        Objects.requireNonNull(nbt, "nbt");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(prototype, "prototype");

        // Law T3: Only inspect fenced compound
        TagNode fencedNode = nbt.get(FENCED_NAMESPACE);
        if (fencedNode == null) {
            return CarrierSnapshot.empty();
        }
        if (!(fencedNode instanceof TagNode.TagCompound)) {
            // Unreadable non-compound envelope: preserve without crashing or losing data
            RecoveryRecord rec = RecoveryRecord.of("mycelium$c", fencedNode,
                    RecoveryRecord.FailureCategory.UNREADABLE_ENVELOPE, "Non-compound fenced envelope");
            return new CarrierSnapshot(Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(),
                    Collections.singletonList(rec), Optional.of(fencedNode), Collections.emptyMap());
        }

        TagNode.TagCompound fenced = (TagNode.TagCompound) fencedNode;
        Map<ComponentId, Object> components = new TreeMap<>();
        Map<String, TagNode> unmapped = new TreeMap<>();
        Set<ComponentId> removals = new TreeSet<>();
        List<RecoveryRecord> recoveryRecords = new ArrayList<>();
        Optional<TagNode> rawEnvelope = Optional.empty();
        Map<ComponentId, TagNode.TagCompound> retainedResiduals = new TreeMap<>();

        // Unpack !recovery compound if present
        TagNode recoveryNode = fenced.get("!recovery");
        if (recoveryNode instanceof TagNode.TagCompound recComp) {
            if (recComp.has("raw_envelope")) {
                rawEnvelope = Optional.of(recComp.get("raw_envelope"));
            }
            TagNode recordsList = recComp.get("records");
            if (recordsList instanceof TagNode.TagList rList) {
                for (int i = 0; i < rList.size(); i++) {
                    if (rList.get(i) instanceof TagNode.TagCompound itemComp) {
                        String loc = itemComp.getString("loc").orElse("unknown");
                        TagNode raw = itemComp.get("raw");
                        String catStr = itemComp.getString("cat").orElse(RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD.name());
                        RecoveryRecord.FailureCategory cat;
                        try {
                            cat = RecoveryRecord.FailureCategory.valueOf(catStr);
                        } catch (Exception e) {
                            cat = RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD;
                        }
                        int ver = itemComp.getInt("ver").orElse(1);
                        String diag = itemComp.getString("diag").orElse("");
                        Optional<ComponentId> compId = itemComp.getString("id").flatMap(s -> {
                            try { return Optional.of(ComponentId.of(s)); } catch (Exception e) { return Optional.empty(); }
                        });
                        if (raw != null) {
                            recoveryRecords.add(new RecoveryRecord(loc, raw, cat, compId, ver, diag));
                        }
                    }
                }
            }
        }

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
                        recoveryRecords.add(RecoveryRecord.of("!removed[" + i + "]", elem,
                                RecoveryRecord.FailureCategory.MALFORMED_CONTROL_SYNTAX, "Invalid component ID in removals: " + ts.value()));
                    }
                } else {
                    invalidElements.add(elem);
                    recoveryRecords.add(RecoveryRecord.of("!removed[" + i + "]", elem,
                                RecoveryRecord.FailureCategory.MALFORMED_CONTROL_SYNTAX, "Non-string element in removals"));
                }
            }
            if (!invalidElements.isEmpty()) {
                unmapped.put("!removed_quarantine", new TagNode.TagList(invalidElements));
            }
        } else if (removedNode != null) {
            // Anti-Data Loss Quarantine: preserve malformed scalar/non-list !removed under distinct key
            unmapped.put("!removed_quarantine", removedNode);
            recoveryRecords.add(RecoveryRecord.of("!removed", removedNode,
                    RecoveryRecord.FailureCategory.MALFORMED_CONTROL_SYNTAX, "Malformed non-list !removed node"));
        }

        Map<ComponentId, List<Map.Entry<String, TagNode>>> canonicalGroups = new LinkedHashMap<>();

        for (Map.Entry<String, TagNode> entry : fenced.entries().entrySet()) {
            String key = entry.getKey();
            if (key.equals("!removed") || key.equals("!recovery")) continue;

            TagNode node = entry.getValue();

            ComponentId id;
            try {
                id = ComponentId.of(key);
            } catch (IllegalArgumentException e) {
                // Malformed ID or special control key
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
                for (Map.Entry<String, TagNode> raw : rawEntries) {
                    unmapped.put(raw.getKey(), raw.getValue());
                    recoveryRecords.add(RecoveryRecord.of(raw.getKey(), raw.getValue(),
                            RecoveryRecord.FailureCategory.ALIAS_COLLISION, id, "Alias collision on " + id));
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
                    if (def.fieldAwareCodec().isPresent() && node instanceof TagNode.TagCompound tc) {
                        FieldAwareCodec<?> fac = def.fieldAwareCodec().get();
                        FieldAwareCodec.DecodedResult<?> res = fac.decodeFieldAware(tc);
                        components.put(id, res.value());
                        if (!res.remainder().isEmpty()) {
                            // only what the lens did not know: nothing left over is nothing retained, so a value
                            // read and written back is the snapshot it was (T1)
                            retainedResiduals.put(id, res.remainder());
                        }
                    } else {
                        Object decoded = def.decodeValue(node);
                        components.put(id, decoded);
                    }
                    // Mutual exclusivity: a valid decoded value supersedes any simultaneous negative removal
                    removals.remove(id);
                } catch (Exception e) {
                    // Law T5: Diagnostic rejection on corruption - does not throw unchecked exception
                    unmapped.put(key, node);
                    recoveryRecords.add(RecoveryRecord.of(key, node,
                            RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD, id, e.getMessage()));
                }
            } else {
                // Law T4: Foreign tag preservation - keep unknown tags
                unmapped.put(key, node);
            }
        }

        return new CarrierSnapshot(components, unmapped, removals, recoveryRecords, rawEnvelope, retainedResiduals);
    }
}
