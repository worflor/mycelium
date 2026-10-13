package mycelium.item_component.v1.model;

import java.util.*;

/**
 * The standard pure implementation of {@link FencedCarrier}, over a {@link RosettaTranslator} (the standard one unless
 * given another). Ships in the model package so a consumer receives the whole carrier on {@code myceliumImport}.
 *
 * <p>There is one decode path: {@link #read(TagNode.TagCompound)} builds the snapshot (fence, then migration), and the
 * single-component read is that snapshot looked up, so the two cannot disagree (T15).
 */
public final class StandardFencedCarrier implements FencedCarrier {
    private static final String FENCE = RosettaTranslator.FENCED_NAMESPACE;

    private final RosettaTranslator translator;
    private final ComponentRegistry registry;
    private final LegacyKeyMigration migration;

    private StandardFencedCarrier(RosettaTranslator translator, ComponentRegistry registry, LegacyKeyMigration migration) {
        this.translator = Objects.requireNonNull(translator, "translator");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.migration = Objects.requireNonNull(migration, "migration");
        for (LegacyKeyMigration.ComponentRule<?> rule : migration.rules()) {
            if (!registry.has(rule.target().id())) {
                throw new IllegalArgumentException("The migration targets " + rule.target().id() + ", which the registry does not hold");
            }
        }
    }

    public static StandardFencedCarrier of(ComponentRegistry registry, LegacyKeyMigration migration) {
        return new StandardFencedCarrier(StandardRosettaTranslator.INSTANCE, registry, migration);
    }

    public static StandardFencedCarrier of(RosettaTranslator translator, ComponentRegistry registry, LegacyKeyMigration migration) {
        return new StandardFencedCarrier(translator, registry, migration);
    }

    @Override
    public ComponentRegistry registry() {
        return registry;
    }

    @Override
    public LegacyKeyMigration migration() {
        return migration;
    }

    // ---------------------------------------------------------------------------------------------------- reading

    @Override
    public CarrierSnapshot read(TagNode.TagCompound root) {
        Objects.requireNonNull(root, "root");
        CarrierSnapshot snapshot = translator.fromLegacyNbt(fenceOnly(root), registry);
        for (LegacyKeyMigration.ComponentRule<?> rule : migration.rules()) {
            snapshot = migrate(rule, root, snapshot);
        }
        return snapshot;
    }

    private <T> CarrierSnapshot migrate(LegacyKeyMigration.ComponentRule<T> rule, TagNode.TagCompound root, CarrierSnapshot snapshot) {
        Map<LegacyKeyMigration.Key, TagNode> found = rule.found(root);
        if (found.isEmpty()) {
            return snapshot;
        }
        ComponentId id = rule.target().id();
        if (snapshot.has(id) || snapshot.isRemoved(id) || snapshot.unmappedTags().containsKey(id.toString())) {
            return snapshot; // the fenced state wins
        }
        try {
            return snapshot.with(id, rule.apply(found));
        } catch (CodecException | RuntimeException e) {
            CarrierSnapshot out = snapshot;
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            for (Map.Entry<LegacyKeyMigration.Key, TagNode> old : found.entrySet()) {
                out = out.withUnmappedTag(LegacyKeyMigration.QUARANTINE_PREFIX + old.getKey(), old.getValue())
                        .withRecovery(RecoveryRecord.of(old.getKey().toString(), old.getValue(),
                                RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD, id, "Unreadable old key: " + why));
            }
            return out;
        }
    }

    @Override
    public <T> ComponentRead<T> read(TagNode.TagCompound root, ComponentDefinition<T> def) {
        Objects.requireNonNull(def, "def");
        return classify(root, read(root), def);
    }

    private <T> ComponentRead<T> classify(TagNode.TagCompound root, CarrierSnapshot snapshot, ComponentDefinition<T> def) {
        ComponentId id = def.id();
        if (snapshot.has(id)) {
            return new ComponentRead.Present<>(snapshot.get(def));
        }
        if (snapshot.isRemoved(id)) {
            return new ComponentRead.Removed<>();
        }
        TagNode envelope = unreadableEnvelope(root);
        if (envelope != null) {
            return new ComponentRead.Quarantined<>(RecoveryRecord.of(FENCE, envelope,
                    RecoveryRecord.FailureCategory.UNREADABLE_ENVELOPE, "Non-compound fenced envelope"));
        }
        for (RecoveryRecord rec : snapshot.recoveryRecords()) {
            // a record is state only while what it is about is still stored; evidence kept beside a write is history
            boolean live = snapshot.unmappedTags().containsKey(rec.location())
                    || snapshot.unmappedTags().containsKey(LegacyKeyMigration.QUARANTINE_PREFIX + rec.location());
            if (live && rec.componentId().isPresent() && rec.componentId().get().equals(id)) {
                return new ComponentRead.Quarantined<>(rec);
            }
        }
        TagNode raw = snapshot.unmappedTags().get(id.toString());
        if (raw != null) {
            return new ComponentRead.Quarantined<>(RecoveryRecord.of(id.toString(), raw,
                    RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD, id, "Unreadable entry"));
        }
        return new ComponentRead.Absent<>();
    }

    // ---------------------------------------------------------------------------------------------------- writing

    @Override
    public WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode) {
        return write(root, snapshot, mode, Set.of());
    }

    @Override
    public <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        Objects.requireNonNull(def, "def");
        Objects.requireNonNull(value, "value");
        CarrierSnapshot before = read(root);
        CarrierSnapshot next = before.with(def, value);
        // evidence an earlier write kept about this component stays; `with` would drop it with the entry it replaced
        for (RecoveryRecord rec : before.recoveryRecords()) {
            boolean kept = rec.componentId().isPresent() && rec.componentId().get().equals(def.id())
                    && !before.unmappedTags().containsKey(rec.location()) && !before.unmappedTags().containsKey(
                    LegacyKeyMigration.QUARANTINE_PREFIX + rec.location());
            if (kept) {
                next = next.withRecovery(rec);
            }
        }
        return write(root, next, mode, Set.of());
    }

    @Override
    public WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def, OnQuarantine mode) {
        Objects.requireNonNull(def, "def");
        ComponentId id = def.id();
        CarrierSnapshot snapshot = read(root);
        if (!snapshot.has(id) && !snapshot.isRemoved(id)) {
            if (unreadableEnvelope(root) != null) {
                // nothing readable to clear, and the component cannot be told apart from what is unreadable
                return mode == OnQuarantine.REFUSE
                        ? new WriteOutcome.Refused(WriteOutcome.Reason.ENVELOPE_QUARANTINED,
                        "The fenced envelope is unreadable, so " + id + " cannot be told apart from it", root)
                        : new WriteOutcome.Written(root, false);
            }
            if (snapshot.unmappedTags().containsKey(id.toString())) {
                if (mode == OnQuarantine.REFUSE) {
                    return new WriteOutcome.Refused(WriteOutcome.Reason.COMPONENT_QUARANTINED,
                            id + " is stored but unreadable; clearing it would drop it", root);
                }
                CarrierSnapshot next = snapshot.withoutUnmappedTag(id.toString());
                if (mode == OnQuarantine.REPLACE) {
                    next = next.withoutRecoveryFor(id);
                }
                return write(root, next, mode, Set.of(id));
            }
        }
        return write(root, snapshot.without(id), mode, Set.of(id));
    }

    private WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode, Set<ComponentId> cleared) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(mode, "mode");
        CarrierSnapshot snap = snapshot;

        // The unreadable envelope: it is the snapshot's raw envelope, or the write must say what it does with it.
        TagNode envelope = unreadableEnvelope(root);
        if (envelope != null) {
            Optional<TagNode> raw = snap.rawEnvelope();
            if (raw.isPresent() && !raw.get().equals(envelope) && mode != OnQuarantine.REPLACE) {
                return new WriteOutcome.Refused(WriteOutcome.Reason.ENVELOPE_QUARANTINED,
                        "The snapshot carries a different raw envelope than the one stored", root);
            }
            if (raw.isEmpty() && mode == OnQuarantine.KEEP) {
                snap = snap.withRawEnvelope(envelope);
            }
            if (mode == OnQuarantine.REPLACE) {
                snap = discardEnvelope(snap, envelope);
            }
        }

        TagNode.TagCompound out = translator.toLegacyNbt(snap, registry);
        TagNode fence = out.get(FENCE);

        if (envelope != null && mode == OnQuarantine.REFUSE && !envelope.equals(fence)) {
            return new WriteOutcome.Refused(WriteOutcome.Reason.ENVELOPE_QUARANTINED,
                    "The fenced envelope is unreadable; writing would move or discard it", root);
        }

        // Unreadable data in a readable fence: still carried after the write, or the mode says what happens.
        if (root.get(FENCE) instanceof TagNode.TagCompound) {
            CarrierSnapshot stored = translator.fromLegacyNbt(fenceOnly(root), registry);
            boolean added = false;
            for (Map.Entry<String, TagNode> evidence : evidence(stored).entrySet()) {
                if (carries(fence, evidence.getKey(), evidence.getValue())) {
                    continue;
                }
                if (mode == OnQuarantine.REFUSE) {
                    return new WriteOutcome.Refused(evidence.getKey().equals("raw_envelope")
                            ? WriteOutcome.Reason.ENVELOPE_QUARANTINED : WriteOutcome.Reason.COMPONENT_QUARANTINED,
                            "Writing would drop the unreadable " + evidence.getKey(), root);
                }
                if (mode == OnQuarantine.KEEP) {
                    ComponentId owner = idOf(evidence.getKey());
                    snap = evidence.getKey().equals("raw_envelope")
                            ? snap.withRawEnvelope(evidence.getValue())
                            : snap.withRecovery(owner == null
                            ? RecoveryRecord.of(evidence.getKey(), evidence.getValue(),
                            RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD, "Kept by a write over it")
                            : RecoveryRecord.of(evidence.getKey(), evidence.getValue(),
                            RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD, owner, "Kept by a write over it"));
                    added = true;
                }
            }
            if (added) {
                out = translator.toLegacyNbt(snap, registry);
                fence = out.get(FENCE);
            }
        }

        // The root: every key kept but the fence and the old keys this write accounts for.
        TagNode.TagCompound result = root.without(FENCE);
        for (LegacyKeyMigration.ComponentRule<?> rule : migration.rules()) {
            ComponentId id = rule.target().id();
            for (LegacyKeyMigration.Key key : rule.found(root).keySet()) {
                boolean accounted = snap.has(id) || snap.isRemoved(id) || cleared.contains(id)
                        || snap.unmappedTags().containsKey(LegacyKeyMigration.QUARANTINE_PREFIX + key);
                if (accounted) {
                    result = key.remove(result);
                }
            }
        }
        for (LegacyKeyMigration.Key key : migration.drops()) {
            result = key.remove(result);
        }
        if (fence != null) {
            result = result.with(FENCE, fence);
        }
        return new WriteOutcome.Written(result, !result.equals(root));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** The snapshot without the unreadable envelope it carries (as raw envelope and as the record read made of it). */
    private static CarrierSnapshot discardEnvelope(CarrierSnapshot snapshot, TagNode envelope) {
        List<RecoveryRecord> records = new ArrayList<>();
        for (RecoveryRecord rec : snapshot.recoveryRecords()) {
            if (!rec.rawNode().equals(envelope)) {
                records.add(rec);
            }
        }
        return new CarrierSnapshot(snapshot.components(), snapshot.unmappedTags(), snapshot.removals(), records,
                Optional.empty(), snapshot.retainedResiduals());
    }

    private static TagNode.TagCompound fenceOnly(TagNode.TagCompound root) {
        TagNode fence = root.get(FENCE);
        return fence == null ? TagNode.TagCompound.empty() : TagNode.TagCompound.empty().with(FENCE, fence);
    }

    /** The fenced entry when it is stored but is not a compound; otherwise null. */
    private static TagNode unreadableEnvelope(TagNode.TagCompound root) {
        TagNode fence = root.get(FENCE);
        return fence != null && !(fence instanceof TagNode.TagCompound) ? fence : null;
    }

    /** What a stored fence holds that is unreadable and so must not vanish: key (or location) to raw. */
    private Map<String, TagNode> evidence(CarrierSnapshot stored) {
        Map<String, TagNode> evidence = new LinkedHashMap<>();
        for (Map.Entry<String, TagNode> e : stored.unmappedTags().entrySet()) {
            String key = e.getKey();
            if (key.startsWith(LegacyKeyMigration.QUARANTINE_PREFIX) || key.equals("!removed_quarantine")
                    || (idOf(key) != null && registry.has(idOf(key)))) {
                evidence.put(key, e.getValue());
            }
        }
        for (RecoveryRecord rec : stored.recoveryRecords()) {
            if (rec.category() == RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD && !evidence.containsValue(rec.rawNode())) {
                evidence.putIfAbsent(rec.location(), rec.rawNode());
            }
        }
        stored.rawEnvelope().ifPresent(raw -> evidence.put("raw_envelope", raw));
        return evidence;
    }

    /** Whether the written fence still holds {@code raw}: at its key, or in the recovery compound. */
    private static boolean carries(TagNode fence, String key, TagNode raw) {
        if (!(fence instanceof TagNode.TagCompound fenced)) {
            return false;
        }
        if (raw.equals(fenced.get(key))) {
            return true;
        }
        if (fenced.get("!recovery") instanceof TagNode.TagCompound recovery) {
            if (raw.equals(recovery.get("raw_envelope"))) {
                return true;
            }
            if (recovery.get("records") instanceof TagNode.TagList records) {
                for (int i = 0; i < records.size(); i++) {
                    if (records.get(i) instanceof TagNode.TagCompound rec && raw.equals(rec.get("raw"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ComponentId idOf(String key) {
        try {
            return ComponentId.of(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
