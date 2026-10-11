package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Immutable snapshot of an item's carrier state: strongly typed component values,
 * plus preserved unmapped tags from future or third-party formats.
 */
public final class CarrierSnapshot {
    private final Map<ComponentId, Object> components;
    private final Map<String, TagNode> unmappedTags;

    public CarrierSnapshot(Map<ComponentId, Object> components, Map<String, TagNode> unmappedTags) {
        this.components = components == null || components.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new TreeMap<>(components));
        this.unmappedTags = unmappedTags == null || unmappedTags.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new TreeMap<>(unmappedTags));
    }

    public static final CarrierSnapshot EMPTY = new CarrierSnapshot(Collections.emptyMap(), Collections.emptyMap());

    public static CarrierSnapshot empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return components.isEmpty() && unmappedTags.isEmpty();
    }

    public boolean has(ComponentId id) {
        return components.containsKey(id);
    }

    public Object get(ComponentId id) {
        return components.get(id);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ComponentDefinition<T> def) {
        Object val = components.get(def.id());
        return val != null ? (T) val : def.defaultValue();
    }

    public Map<ComponentId, Object> components() {
        return components;
    }

    public Map<String, TagNode> unmappedTags() {
        return unmappedTags;
    }

    public CarrierSnapshot with(ComponentId id, Object value) {
        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(value, "value"));
        return new CarrierSnapshot(nextComponents, unmappedTags);
    }

    public CarrierSnapshot without(ComponentId id) {
        if (!components.containsKey(id)) return this;
        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.remove(id);
        return new CarrierSnapshot(nextComponents, unmappedTags);
    }

    /** Returns a canonical snapshot with all default-valued components elided (Law T2). */
    @SuppressWarnings("unchecked")
    public CarrierSnapshot normalized(ComponentRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Map<ComponentId, Object> norm = new TreeMap<>();
        for (Map.Entry<ComponentId, Object> e : components.entrySet()) {
            Optional<ComponentDefinition<?>> defOpt = registry.get(e.getKey());
            if (defOpt.isEmpty()) {
                norm.put(e.getKey(), e.getValue());
            } else {
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();
                if (!def.isDefault(e.getValue())) {
                    norm.put(e.getKey(), e.getValue());
                }
            }
        }
        return new CarrierSnapshot(norm, unmappedTags);
    }

    public CarrierSnapshot withUnmappedTag(String key, TagNode tag) {
        Map<String, TagNode> nextUnmapped = new TreeMap<>(unmappedTags);
        nextUnmapped.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(tag, "tag"));
        return new CarrierSnapshot(components, nextUnmapped);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CarrierSnapshot that)) return false;
        return Objects.equals(components, that.components) && Objects.equals(unmappedTags, that.unmappedTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(components, unmappedTags);
    }

    @Override
    public String toString() {
        return "CarrierSnapshot[components=" + components + ", unmappedTags=" + unmappedTags + "]";
    }
}
