package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Pure utility providing compositional value semantics: deep freezing, defensive cloning,
 * deep equality, and deep hash coding across nested primitives, arrays, collections, and records.
 */
public final class ValueSemantics {
    private ValueSemantics() {}

    /**
     * Deeply freezes an object into an immutable representation so that no external
     * mutation of caller references can alter the snapshot's state.
     */
    @SuppressWarnings("unchecked")
    public static <T> T freeze(T o) {
        if (o == null) return null;
        if (o instanceof byte[]) return (T) ((byte[]) o).clone();
        if (o instanceof short[]) return (T) ((short[]) o).clone();
        if (o instanceof int[]) return (T) ((int[]) o).clone();
        if (o instanceof long[]) return (T) ((long[]) o).clone();
        if (o instanceof float[]) return (T) ((float[]) o).clone();
        if (o instanceof double[]) return (T) ((double[]) o).clone();
        if (o instanceof boolean[]) return (T) ((boolean[]) o).clone();
        if (o instanceof char[]) return (T) ((char[]) o).clone();
        if (o instanceof Object[]) {
            Object[] arr = (Object[]) o;
            Object[] copy = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                copy[i] = freeze(arr[i]);
            }
            return (T) copy;
        }
        if (o instanceof List<?>) {
            List<?> l = (List<?>) o;
            List<Object> frozen = new ArrayList<>(l.size());
            for (Object item : l) {
                frozen.add(freeze(item));
            }
            return (T) Collections.unmodifiableList(frozen);
        }
        if (o instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) o;
            Map<Object, Object> frozen = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                frozen.put(freeze(e.getKey()), freeze(e.getValue()));
            }
            return (T) Collections.unmodifiableMap(frozen);
        }
        if (o instanceof Set<?>) {
            Set<?> s = (Set<?>) o;
            Set<Object> frozen = new LinkedHashSet<>(s.size());
            for (Object item : s) {
                frozen.add(freeze(item));
            }
            return (T) Collections.unmodifiableSet(frozen);
        }
        return o;
    }

    /**
     * Deeply clones mutable objects (arrays, lists, maps) to safely expose copies through accessors.
     */
    public static <T> T deepClone(T o) {
        return freeze(o);
    }

    /**
     * Compositional value equality that correctly traverses nested arrays, lists, maps, and sets.
     */
    public static boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof short[] && b instanceof short[]) return Arrays.equals((short[]) a, (short[]) b);
        if (a instanceof int[] && b instanceof int[]) return Arrays.equals((int[]) a, (int[]) b);
        if (a instanceof long[] && b instanceof long[]) return Arrays.equals((long[]) a, (long[]) b);
        if (a instanceof float[] && b instanceof float[]) return Arrays.equals((float[]) a, (float[]) b);
        if (a instanceof double[] && b instanceof double[]) return Arrays.equals((double[]) a, (double[]) b);
        if (a instanceof boolean[] && b instanceof boolean[]) return Arrays.equals((boolean[]) a, (boolean[]) b);
        if (a instanceof char[] && b instanceof char[]) return Arrays.equals((char[]) a, (char[]) b);

        if (a instanceof Object[] && b instanceof Object[]) {
            Object[] arrA = (Object[]) a;
            Object[] arrB = (Object[]) b;
            if (arrA.length != arrB.length) return false;
            for (int i = 0; i < arrA.length; i++) {
                if (!deepEquals(arrA[i], arrB[i])) return false;
            }
            return true;
        }

        if (a instanceof List<?> && b instanceof List<?>) {
            List<?> lA = (List<?>) a;
            List<?> lB = (List<?>) b;
            if (lA.size() != lB.size()) return false;
            for (int i = 0; i < lA.size(); i++) {
                if (!deepEquals(lA.get(i), lB.get(i))) return false;
            }
            return true;
        }

        if (a instanceof Map<?, ?> && b instanceof Map<?, ?>) {
            Map<?, ?> mA = (Map<?, ?>) a;
            Map<?, ?> mB = (Map<?, ?>) b;
            if (mA.size() != mB.size()) return false;
            for (Map.Entry<?, ?> e : mA.entrySet()) {
                if (!mB.containsKey(e.getKey())) return false;
                if (!deepEquals(e.getValue(), mB.get(e.getKey()))) return false;
            }
            return true;
        }

        if (a instanceof Set<?> && b instanceof Set<?>) {
            Set<?> sA = (Set<?>) a;
            Set<?> sB = (Set<?>) b;
            if (sA.size() != sB.size()) return false;
            for (Object itemA : sA) {
                boolean found = false;
                for (Object itemB : sB) {
                    if (deepEquals(itemA, itemB)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }

        return Objects.equals(a, b);
    }

    /**
     * Compositional deep hash code consistent with {@link #deepEquals(Object, Object)}.
     */
    public static int deepHashCode(Object o) {
        if (o == null) return 0;
        if (o instanceof byte[]) return Arrays.hashCode((byte[]) o);
        if (o instanceof short[]) return Arrays.hashCode((short[]) o);
        if (o instanceof int[]) return Arrays.hashCode((int[]) o);
        if (o instanceof long[]) return Arrays.hashCode((long[]) o);
        if (o instanceof float[]) return Arrays.hashCode((float[]) o);
        if (o instanceof double[]) return Arrays.hashCode((double[]) o);
        if (o instanceof boolean[]) return Arrays.hashCode((boolean[]) o);
        if (o instanceof char[]) return Arrays.hashCode((char[]) o);

        if (o instanceof Object[]) {
            int h = 1;
            for (Object item : (Object[]) o) {
                h = 31 * h + deepHashCode(item);
            }
            return h;
        }

        if (o instanceof List<?>) {
            int h = 1;
            for (Object item : (List<?>) o) {
                h = 31 * h + deepHashCode(item);
            }
            return h;
        }

        if (o instanceof Map<?, ?>) {
            int h = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                h += (deepHashCode(e.getKey()) ^ deepHashCode(e.getValue()));
            }
            return h;
        }

        if (o instanceof Set<?>) {
            int h = 0;
            for (Object item : (Set<?>) o) {
                h += deepHashCode(item);
            }
            return h;
        }

        return o.hashCode();
    }

    /**
     * Wraps a component map in an unmodifiable defensive view where getting any value
     * returns a cloned copy of mutable data, preventing leaks of internal snapshot state.
     */
    public static Map<ComponentId, Object> defensiveMapView(Map<ComponentId, Object> internal) {
        return new AbstractMap<ComponentId, Object>() {
            @Override
            public int size() {
                return internal.size();
            }

            @Override
            public boolean isEmpty() {
                return internal.isEmpty();
            }

            @Override
            public boolean containsKey(Object key) {
                return internal.containsKey(key);
            }

            @Override
            public boolean containsValue(Object value) {
                for (Object v : internal.values()) {
                    if (deepEquals(v, value)) return true;
                }
                return false;
            }

            @Override
            public Object get(Object key) {
                return deepClone(internal.get(key));
            }

            @Override
            public Set<Entry<ComponentId, Object>> entrySet() {
                Set<Entry<ComponentId, Object>> entries = new LinkedHashSet<>();
                for (Entry<ComponentId, Object> e : internal.entrySet()) {
                    entries.add(new SimpleImmutableEntry<>(e.getKey(), deepClone(e.getValue())));
                }
                return Collections.unmodifiableSet(entries);
            }

            @Override
            public Set<ComponentId> keySet() {
                return Collections.unmodifiableSet(internal.keySet());
            }

            @Override
            public Collection<Object> values() {
                List<Object> vals = new ArrayList<>(internal.size());
                for (Object v : internal.values()) {
                    vals.add(deepClone(v));
                }
                return Collections.unmodifiableList(vals);
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Map<?, ?> other)) return false;
                if (size() != other.size()) return false;
                for (Entry<ComponentId, Object> e : internal.entrySet()) {
                    if (!other.containsKey(e.getKey())) return false;
                    if (!deepEquals(e.getValue(), other.get(e.getKey()))) return false;
                }
                return true;
            }

            @Override
            public int hashCode() {
                int h = 0;
                for (Entry<ComponentId, Object> e : internal.entrySet()) {
                    h += Objects.hashCode(e.getKey()) ^ deepHashCode(e.getValue());
                }
                return h;
            }
        };
    }
}
