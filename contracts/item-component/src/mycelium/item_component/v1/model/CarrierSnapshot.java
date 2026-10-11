package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Immutable snapshot of an item's carrier state: strongly typed component values,
 * explicit component removals (negative patches), plus preserved unmapped tags
 * from future or third-party formats.
 */
public final class CarrierSnapshot {
    private final Map<ComponentId, Object> components;
    private final Map<String, TagNode> unmappedTags;
    private final Set<ComponentId> removals;

    public CarrierSnapshot(Map<ComponentId, Object> components, Map<String, TagNode> unmappedTags) {
        this(components, unmappedTags, Collections.emptySet());
    }

    public CarrierSnapshot(Map<ComponentId, Object> components, Map<String, TagNode> unmappedTags, Set<ComponentId> removals) {
        if (components == null || components.isEmpty()) {
            this.components = Collections.emptyMap();
        } else {
            Map<ComponentId, Object> copy = new TreeMap<>();
            for (Map.Entry<ComponentId, Object> e : components.entrySet()) {
                copy.put(e.getKey(), cloneIfArray(e.getValue()));
            }
            this.components = Collections.unmodifiableMap(copy);
        }
        this.unmappedTags = unmappedTags == null || unmappedTags.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new TreeMap<>(unmappedTags));
        this.removals = removals == null || removals.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new TreeSet<>(removals));
    }

    public static final CarrierSnapshot EMPTY = new CarrierSnapshot(Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet());

    public static CarrierSnapshot empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return components.isEmpty() && unmappedTags.isEmpty() && removals.isEmpty();
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

    public Object get(ComponentId id) {
        return cloneIfArray(components.get(id));
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ComponentDefinition<T> def) {
        Object val = components.get(def.id());
        return val != null ? (T) cloneIfArray(val) : (T) cloneIfArray(def.defaultValue());
    }


    public Map<ComponentId, Object> components() {
        return components;
    }

    public Map<String, TagNode> unmappedTags() {
        return unmappedTags;
    }

    public CarrierSnapshot with(ComponentId id, Object value) {
        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.put(Objects.requireNonNull(id, "id"), cloneIfArray(Objects.requireNonNull(value, "value")));
        Set<ComponentId> nextRemovals = new TreeSet<>(removals);
        nextRemovals.remove(id);
        Map<String, TagNode> nextUnmapped = new TreeMap<>(unmappedTags);
        nextUnmapped.remove(id.toString());
        return new CarrierSnapshot(nextComponents, nextUnmapped, nextRemovals);
    }

    public <T> CarrierSnapshot with(ComponentDefinition<T> def, T value) {
        return with(def.id(), value);
    }

    public CarrierSnapshot withRemoval(ComponentId id) {
        Objects.requireNonNull(id, "id");
        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.remove(id);
        Set<ComponentId> nextRemovals = new TreeSet<>(removals);
        nextRemovals.add(id);
        Map<String, TagNode> nextUnmapped = new TreeMap<>(unmappedTags);
        nextUnmapped.remove(id.toString());
        return new CarrierSnapshot(nextComponents, nextUnmapped, nextRemovals);
    }

    public CarrierSnapshot without(ComponentId id) {
        if (!components.containsKey(id) && !removals.contains(id)) return this;
        Map<ComponentId, Object> nextComponents = new TreeMap<>(components);
        nextComponents.remove(id);
        Set<ComponentId> nextRemovals = new TreeSet<>(removals);
        nextRemovals.remove(id);
        return new CarrierSnapshot(nextComponents, unmappedTags, nextRemovals);
    }

    private static Object cloneIfArray(Object val) {
        if (val == null) return null;
        if (val instanceof byte[]) return ((byte[]) val).clone();
        if (val instanceof short[]) return ((short[]) val).clone();
        if (val instanceof int[]) return ((int[]) val).clone();
        if (val instanceof long[]) return ((long[]) val).clone();
        if (val instanceof float[]) return ((float[]) val).clone();
        if (val instanceof double[]) return ((double[]) val).clone();
        if (val instanceof boolean[]) return ((boolean[]) val).clone();
        if (val instanceof Object[]) return ((Object[]) val).clone();
        return val;
    }

    public static final class Builder {
        private final Map<ComponentId, Object> components = new TreeMap<>();
        private final Map<String, TagNode> unmappedTags = new TreeMap<>();
        private final Set<ComponentId> removals = new TreeSet<>();

        public Builder with(ComponentId id, Object value) {
            components.put(Objects.requireNonNull(id, "id"), cloneIfArray(Objects.requireNonNull(value, "value")));
            removals.remove(id);
            unmappedTags.remove(id.toString());
            return this;
        }

        public <T> Builder with(ComponentDefinition<T> def, T value) {
            return with(def.id(), value);
        }

        public Builder withRemoval(ComponentId id) {
            removals.add(Objects.requireNonNull(id, "id"));
            components.remove(id);
            unmappedTags.remove(id.toString());
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

        public CarrierSnapshot build() {
            return new CarrierSnapshot(components, unmappedTags, removals);
        }
    }


    /** Returns a canonical snapshot with all default-valued components elided (Law T2). */
    @SuppressWarnings("unchecked")
    public CarrierSnapshot normalized(ComponentRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Map<ComponentId, Object> norm = new TreeMap<>();
        for (Map.Entry<ComponentId, Object> e : components.entrySet()) {
            Optional<ComponentDefinition<?>> defOpt = registry.get(e.getKey());
            if (!defOpt.isPresent()) {
                norm.put(e.getKey(), e.getValue());
            } else {
                ComponentDefinition<Object> def = (ComponentDefinition<Object>) defOpt.get();
                if (!def.isDefault(e.getValue())) {
                    norm.put(e.getKey(), e.getValue());
                }
            }
        }
        return new CarrierSnapshot(norm, unmappedTags, removals);
    }

    public CarrierSnapshot withUnmappedTag(String key, TagNode tag) {
        Map<String, TagNode> nextUnmapped = new TreeMap<>(unmappedTags);
        nextUnmapped.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(tag, "tag"));
        return new CarrierSnapshot(components, nextUnmapped, removals);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CarrierSnapshot that)) return false;
        return mapValuesEqual(components, that.components) &&
                Objects.equals(unmappedTags, that.unmappedTags) &&
                Objects.equals(removals, that.removals);
    }

    @Override
    public int hashCode() {
        int result = mapHashCode(components);
        result = 31 * result + Objects.hashCode(unmappedTags);
        result = 31 * result + Objects.hashCode(removals);
        return result;
    }

    private static boolean mapValuesEqual(Map<?, ?> a, Map<?, ?> b) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        for (Map.Entry<?, ?> e : a.entrySet()) {
            Object key = e.getKey();
            if (!b.containsKey(key)) return false;
            if (!deepEquals(e.getValue(), b.get(key))) return false;
        }
        return true;
    }

    private static boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof short[] && b instanceof short[]) return Arrays.equals((short[]) a, (short[]) b);
        if (a instanceof int[] && b instanceof int[]) return Arrays.equals((int[]) a, (int[]) b);
        if (a instanceof long[] && b instanceof long[]) return Arrays.equals((long[]) a, (long[]) b);
        if (a instanceof float[] && b instanceof float[]) return Arrays.equals((float[]) a, (float[]) b);
        if (a instanceof double[] && b instanceof double[]) return Arrays.equals((double[]) a, (double[]) b);
        if (a instanceof boolean[] && b instanceof boolean[]) return Arrays.equals((boolean[]) a, (boolean[]) b);
        if (a instanceof Object[] && b instanceof Object[]) return Arrays.deepEquals((Object[]) a, (Object[]) b);
        return Objects.equals(a, b);
    }

    private static int mapHashCode(Map<?, ?> map) {
        int h = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            int keyHash = Objects.hashCode(entry.getKey());
            int valHash = valueHashCode(entry.getValue());
            h += keyHash ^ valHash;
        }
        return h;
    }

    private static int valueHashCode(Object val) {
        if (val == null) return 0;
        if (val instanceof byte[]) return Arrays.hashCode((byte[]) val);
        if (val instanceof short[]) return Arrays.hashCode((short[]) val);
        if (val instanceof int[]) return Arrays.hashCode((int[]) val);
        if (val instanceof long[]) return Arrays.hashCode((long[]) val);
        if (val instanceof float[]) return Arrays.hashCode((float[]) val);
        if (val instanceof double[]) return Arrays.hashCode((double[]) val);
        if (val instanceof boolean[]) return Arrays.hashCode((boolean[]) val);
        if (val instanceof Object[]) return Arrays.deepHashCode((Object[]) val);
        return val.hashCode();
    }

    @Override
    public String toString() {
        return "CarrierSnapshot[components=" + components + ", unmappedTags=" + unmappedTags + ", removals=" + removals + "]";
    }
}

