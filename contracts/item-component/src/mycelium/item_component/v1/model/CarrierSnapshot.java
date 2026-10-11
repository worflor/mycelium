package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Immutable snapshot of an item's carrier state: strongly typed component values,
 * explicit component removals (negative patches), preserved unmapped tags,
 * structured recovery records, and field-aware residuals.
 */
public final class CarrierSnapshot {
    private final Map<ComponentId, Object> components;
    private final Map<String, TagNode> unmappedTags;
    private final Set<ComponentId> removals;
    private final List<RecoveryRecord> recoveryRecords;
    private final Optional<TagNode> rawEnvelope;
    private final Map<ComponentId, TagNode.TagCompound> retainedResiduals;

    public CarrierSnapshot(Map<ComponentId, Object> components, Map<String, TagNode> unmappedTags) {
        this(components, unmappedTags, Collections.emptySet(), Collections.emptyList(), Optional.empty(), Collections.emptyMap());
    }

    public CarrierSnapshot(Map<ComponentId, Object> components, Map<String, TagNode> unmappedTags, Set<ComponentId> removals) {
        this(components, unmappedTags, removals, Collections.emptyList(), Optional.empty(), Collections.emptyMap());
    }

    public CarrierSnapshot(
            Map<ComponentId, Object> components,
            Map<String, TagNode> unmappedTags,
            Set<ComponentId> removals,
            List<RecoveryRecord> recoveryRecords,
            Optional<TagNode> rawEnvelope,
            Map<ComponentId, TagNode.TagCompound> retainedResiduals
    ) {
        if (components == null || components.isEmpty()) {
            this.components = Collections.emptyMap();
        } else {
            Map<ComponentId, Object> copy = new TreeMap<>();
            for (Map.Entry<ComponentId, Object> e : components.entrySet()) {
                copy.put(Objects.requireNonNull(e.getKey(), "componentId"),
                        ValueSemantics.freeze(Objects.requireNonNull(e.getValue(), "componentValue")));
            }
            this.components = Collections.unmodifiableMap(copy);
        }
        this.unmappedTags = unmappedTags == null || unmappedTags.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new TreeMap<>(unmappedTags));
        this.removals = removals == null || removals.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new TreeSet<>(removals));
        this.recoveryRecords = recoveryRecords == null || recoveryRecords.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(recoveryRecords));
        this.rawEnvelope = Objects.requireNonNull(rawEnvelope != null ? rawEnvelope : Optional.empty(), "rawEnvelope");
        this.retainedResiduals = retainedResiduals == null || retainedResiduals.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new TreeMap<>(retainedResiduals));

        // Invariant: mutual exclusivity between present components and explicit removals
        for (ComponentId rem : this.removals) {
            if (this.components.containsKey(rem)) {
                throw new IllegalArgumentException("Contradictory state: component " + rem + " cannot be both present and removed");
            }
        }
    }

    public static final CarrierSnapshot EMPTY = new CarrierSnapshot(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(),
            Collections.emptyList(), Optional.empty(), Collections.emptyMap());

    public static CarrierSnapshot empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return components.isEmpty() && unmappedTags.isEmpty() && removals.isEmpty()
                && recoveryRecords.isEmpty() && rawEnvelope.isEmpty() && retainedResiduals.isEmpty();
    }

    public boolean has(ComponentId id) {
        return components.containsKey(id);
    }

    public boolean isRemoved(ComponentId id) {
        return removals.contains(id);
    }

    public Set<ComponentId> removals() {
        return removals;
    }

    public List<RecoveryRecord> recoveryRecords() {
        return recoveryRecords;
    }

    public Optional<TagNode> rawEnvelope() {
        return rawEnvelope;
    }

    public Map<ComponentId, TagNode.TagCompound> retainedResiduals() {
        return retainedResiduals;
    }

    public Object get(ComponentId id) {
        return ValueSemantics.deepClone(components.get(id));
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ComponentDefinition<T> def) {
        Objects.requireNonNull(def, "def");
        if (isRemoved(def.id())) {
            return null;
        }
        Object val = components.get(def.id());
        return val != null ? def.codec().freeze((T) val) : def.defaultValue();
    }

    public <T> Optional<T> getOptional(ComponentDefinition<T> def) {
        return Optional.ofNullable(get(def));
    }

    public Optional<String> getString(ComponentId id) {
        Object val = get(id);
        return val instanceof String ? Optional.of((String) val) : Optional.empty();
    }

    public Optional<Integer> getInt(ComponentId id) {
        Object val = get(id);
        return val instanceof Integer ? Optional.of((Integer) val) : Optional.empty();
    }

    public Optional<Boolean> getBoolean(ComponentId id) {
        Object val = get(id);
        return val instanceof Boolean ? Optional.of((Boolean) val) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public <T> ComponentPatch<T> readPatch(ComponentId id) {
        if (isRemoved(id)) {
            return ComponentPatch.remove();
        }
        if (has(id)) {
            return ComponentPatch.set((T) get(id));
        }
        return ComponentPatch.inherit();
    }

    public <T> ComponentPatch<T> readPatch(ComponentDefinition<T> def) {
        return readPatch(def.id());
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> readEffective(ComponentId id, ItemPrototype prototype) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prototype, "prototype");
        if (isRemoved(id)) {
            return Optional.empty();
        }
        if (has(id)) {
            return Optional.of((T) get(id));
        }
        return prototype.getBaseComponent(id);
    }

    public <T> Optional<T> readEffective(ComponentDefinition<T> def, ItemPrototype prototype) {
        return readEffective(def.id(), prototype);
    }

    public <T> T readEffectiveOrElse(ComponentId id, ItemPrototype prototype, T fallback) {
        return this.<T>readEffective(id, prototype).orElse(fallback);
    }

    public <T> T readEffectiveOrElse(ComponentDefinition<T> def, ItemPrototype prototype, T fallback) {
        return readEffectiveOrElse(def.id(), prototype, fallback);
    }

    /**
     * Returns an unmodifiable defensive snapshot of the components map.
     * Conforms to the standard Oracle Java Map contract.
     */
    public Map<ComponentId, Object> components() {
        return ValueSemantics.defensiveMapView(components);
    }

    public Map<String, TagNode> unmappedTags() {
        return unmappedTags;
    }

    public CarrierSnapshot with(ComponentId id, Object value) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");

        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.put(id, ValueSemantics.freeze(value));

        Set<ComponentId> nextRemovals = new TreeSet<>(removals);
        nextRemovals.remove(id);

        Map<String, TagNode> nextUnmapped = new TreeMap<>(unmappedTags);
        nextUnmapped.remove(id.toString());
        nextUnmapped.remove(id.path()); // supersede short alias candidate

        // Clear resolved alias/quarantine records for this component
        List<RecoveryRecord> nextRecovery = new ArrayList<>();
        for (RecoveryRecord rec : recoveryRecords) {
            if (rec.componentId().isPresent() && rec.componentId().get().equals(id)) {
                continue;
            }
            nextRecovery.add(rec);
        }

        return new CarrierSnapshot(nextComponents, nextUnmapped, nextRemovals, nextRecovery, rawEnvelope, retainedResiduals);
    }

    public <T> CarrierSnapshot with(ComponentDefinition<T> def, T value) {
        Objects.requireNonNull(def, "def");
        Objects.requireNonNull(value, "value");
        T frozen = def.codec().freeze(value);
        return with(def.id(), frozen);
    }

    public CarrierSnapshot withRemoval(ComponentId id) {
        Objects.requireNonNull(id, "id");
        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.remove(id);

        Set<ComponentId> nextRemovals = new TreeSet<>(removals);
        nextRemovals.add(id);

        Map<String, TagNode> nextUnmapped = new TreeMap<>(unmappedTags);
        nextUnmapped.remove(id.toString());
        nextUnmapped.remove(id.path()); // prevent short alias from resurrecting

        List<RecoveryRecord> nextRecovery = new ArrayList<>();
        for (RecoveryRecord rec : recoveryRecords) {
            if (rec.componentId().isPresent() && rec.componentId().get().equals(id)) {
                continue;
            }
            nextRecovery.add(rec);
        }

        return new CarrierSnapshot(nextComponents, nextUnmapped, nextRemovals, nextRecovery, rawEnvelope, retainedResiduals);
    }

    public CarrierSnapshot without(ComponentId id) {
        if (!components.containsKey(id) && !removals.contains(id)) return this;
        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.remove(id);
        Set<ComponentId> nextRemovals = new TreeSet<>(removals);
        nextRemovals.remove(id);
        return new CarrierSnapshot(nextComponents, unmappedTags, nextRemovals, recoveryRecords, rawEnvelope, retainedResiduals);
    }

    public CarrierSnapshot withRecovery(RecoveryRecord record) {
        Objects.requireNonNull(record, "record");
        List<RecoveryRecord> nextRecovery = new ArrayList<>(recoveryRecords);
        nextRecovery.add(record);
        return new CarrierSnapshot(components, unmappedTags, removals, nextRecovery, rawEnvelope, retainedResiduals);
    }

    public CarrierSnapshot withRawEnvelope(TagNode envelope) {
        return new CarrierSnapshot(components, unmappedTags, removals, recoveryRecords, Optional.ofNullable(envelope), retainedResiduals);
    }

    public CarrierSnapshot withResidual(ComponentId id, TagNode.TagCompound residual) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(residual, "residual");
        Map<ComponentId, TagNode.TagCompound> nextResiduals = new TreeMap<>(retainedResiduals);
        nextResiduals.put(id, residual);
        return new CarrierSnapshot(components, unmappedTags, removals, recoveryRecords, rawEnvelope, nextResiduals);
    }

    public static final class Builder {
        private final Map<ComponentId, Object> components = new TreeMap<>();
        private final Map<String, TagNode> unmappedTags = new TreeMap<>();
        private final Set<ComponentId> removals = new TreeSet<>();
        private final List<RecoveryRecord> recoveryRecords = new ArrayList<>();
        private Optional<TagNode> rawEnvelope = Optional.empty();
        private final Map<ComponentId, TagNode.TagCompound> retainedResiduals = new TreeMap<>();

        public Builder with(ComponentId id, Object value) {
            components.put(Objects.requireNonNull(id, "id"), ValueSemantics.freeze(Objects.requireNonNull(value, "value")));
            removals.remove(id);
            unmappedTags.remove(id.toString());
            unmappedTags.remove(id.path());
            recoveryRecords.removeIf(r -> r.componentId().isPresent() && r.componentId().get().equals(id));
            return this;
        }

        public <T> Builder with(ComponentDefinition<T> def, T value) {
            Objects.requireNonNull(def, "def");
            Objects.requireNonNull(value, "value");
            return with(def.id(), def.codec().freeze(value));
        }

        public Builder withRemoval(ComponentId id) {
            removals.add(Objects.requireNonNull(id, "id"));
            components.remove(id);
            unmappedTags.remove(id.toString());
            unmappedTags.remove(id.path());
            recoveryRecords.removeIf(r -> r.componentId().isPresent() && r.componentId().get().equals(id));
            return this;
        }

        public Builder without(ComponentId id) {
            components.remove(id);
            removals.remove(id);
            return this;
        }

        public Builder withUnmappedTag(String key, TagNode tag) {
            unmappedTags.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(tag, "tag"));
            return this;
        }

        public Builder withRecovery(RecoveryRecord record) {
            recoveryRecords.add(Objects.requireNonNull(record, "record"));
            return this;
        }

        public Builder withRawEnvelope(TagNode envelope) {
            rawEnvelope = Optional.ofNullable(envelope);
            return this;
        }

        public Builder withResidual(ComponentId id, TagNode.TagCompound residual) {
            retainedResiduals.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(residual, "residual"));
            return this;
        }

        public CarrierSnapshot build() {
            return new CarrierSnapshot(components, unmappedTags, removals, recoveryRecords, rawEnvelope, retainedResiduals);
        }
    }

    /** Returns a canonical snapshot with all default-valued components elided (Law T2). */
    public CarrierSnapshot normalized(ComponentRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Map<ComponentId, Object> norm = new TreeMap<>();
        for (Map.Entry<ComponentId, Object> e : components.entrySet()) {
            ComponentId id = e.getKey();
            Object val = e.getValue();
            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();
                if (!def.isDefault(val)) {
                    norm.put(id, val);
                }
            } else {
                norm.put(id, val);
            }
        }
        return new CarrierSnapshot(norm, unmappedTags, removals, recoveryRecords, rawEnvelope, retainedResiduals);
    }

    /**
     * Canonicalizes this snapshot against a given item archetype prototype and schema registry.
     * Satisfies: resolve(P, canonicalize(P, S)) = resolve(P, S).
     */
    public CarrierSnapshot canonicalize(ItemPrototype prototype, ComponentRegistry registry) {
        Objects.requireNonNull(prototype, "prototype");
        Objects.requireNonNull(registry, "registry");
        Map<ComponentId, Object> canon = new TreeMap<>();
        for (Map.Entry<ComponentId, Object> e : components.entrySet()) {
            ComponentId id = e.getKey();
            Object val = e.getValue();
            Optional<Object> protoBase = prototype.getBaseComponent(id);
            if (protoBase.isPresent()) {
                if (!ValueSemantics.deepEquals(protoBase.get(), val)) {
                    canon.put(id, val);
                }
            } else {
                // Prototype lacks this component: setting it is an override over absence!
                // Do NOT elide against definition default; retain it.
                canon.put(id, val);
            }
        }
        return new CarrierSnapshot(canon, unmappedTags, removals, recoveryRecords, rawEnvelope, retainedResiduals);
    }

    public CarrierSnapshot withUnmappedTag(String key, TagNode tag) {
        Map<String, TagNode> nextUnmapped = new TreeMap<>(unmappedTags);
        nextUnmapped.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(tag, "tag"));
        return new CarrierSnapshot(components, nextUnmapped, removals, recoveryRecords, rawEnvelope, retainedResiduals);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CarrierSnapshot that)) return false;
        return ValueSemantics.deepEquals(components, that.components) &&
                Objects.equals(unmappedTags, that.unmappedTags) &&
                Objects.equals(removals, that.removals) &&
                Objects.equals(recoveryRecords, that.recoveryRecords) &&
                Objects.equals(rawEnvelope, that.rawEnvelope) &&
                Objects.equals(retainedResiduals, that.retainedResiduals);
    }

    @Override
    public int hashCode() {
        int result = ValueSemantics.deepHashCode(components);
        result = 31 * result + Objects.hashCode(unmappedTags);
        result = 31 * result + Objects.hashCode(removals);
        result = 31 * result + Objects.hashCode(recoveryRecords);
        result = 31 * result + Objects.hashCode(rawEnvelope);
        result = 31 * result + Objects.hashCode(retainedResiduals);
        return result;
    }

    @Override
    public String toString() {
        return "CarrierSnapshot[components=" + components + ", unmappedTags=" + unmappedTags + ", removals=" + removals
                + ", recovery=" + recoveryRecords + ", rawEnvelope=" + rawEnvelope + "]";
    }
}
