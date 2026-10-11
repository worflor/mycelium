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
            Object[] copy = (Object[]) java.lang.reflect.Array.newInstance(arr.getClass().getComponentType(), arr.length);
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

        // Deep freeze records containing mutable fields via reflection
        return freezeRecord(o);
    }

    @SuppressWarnings("unchecked")
    private static <T> T freezeRecord(T o) {
        try {
            boolean isRecord = false;
            try {
                java.lang.reflect.Method isRecordMethod = Class.class.getMethod("isRecord");
                isRecord = Boolean.TRUE.equals(isRecordMethod.invoke(o.getClass()));
            } catch (Throwable ignored) {
            }
            if (!isRecord) {
                Class<?> superclass = o.getClass().getSuperclass();
                if (superclass != null && (superclass.getName().equals("java.lang.Record") || superclass.getName().endsWith("Record"))) {
                    isRecord = true;
                }
            }

            if (isRecord) {
                try {
                    java.lang.reflect.Method getComponentsMethod = Class.class.getMethod("getRecordComponents");
                    Object[] components = (Object[]) getComponentsMethod.invoke(o.getClass());
                    if (components != null && components.length > 0) {
                        Class<?>[] paramTypes = new Class<?>[components.length];
                        Object[] args = new Object[components.length];
                        for (int i = 0; i < components.length; i++) {
                            java.lang.reflect.Method getType = components[i].getClass().getMethod("getType");
                            java.lang.reflect.Method getAccessor = components[i].getClass().getMethod("getAccessor");
                            paramTypes[i] = (Class<?>) getType.invoke(components[i]);
                            java.lang.reflect.Method accessor = (java.lang.reflect.Method) getAccessor.invoke(components[i]);
                            accessor.setAccessible(true);
                            args[i] = freeze(accessor.invoke(o));
                        }
                        java.lang.reflect.Constructor<?> ctor = o.getClass().getDeclaredConstructor(paramTypes);
                        ctor.setAccessible(true);
                        return (T) ctor.newInstance(args);
                    }
                } catch (NoSuchMethodException e) {
                    // Java 8 / downgraded bytecode fallback: inspect declared instance fields
                    List<java.lang.reflect.Field> instanceFields = new ArrayList<>();
                    for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            instanceFields.add(f);
                        }
                    }
                    if (!instanceFields.isEmpty()) {
                        Class<?>[] paramTypes = new Class<?>[instanceFields.size()];
                        Object[] args = new Object[instanceFields.size()];
                        for (int i = 0; i < instanceFields.size(); i++) {
                            java.lang.reflect.Field f = instanceFields.get(i);
                            f.setAccessible(true);
                            paramTypes[i] = f.getType();
                            args[i] = freeze(f.get(o));
                        }
                        java.lang.reflect.Constructor<?> ctor = o.getClass().getDeclaredConstructor(paramTypes);
                        ctor.setAccessible(true);
                        return (T) ctor.newInstance(args);
                    }
                }
            }
        } catch (Throwable ignored) {
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
            List<Object> bList = new ArrayList<>(sB);
            boolean[] matched = new boolean[bList.size()];
            for (Object itemA : sA) {
                boolean found = false;
                for (int i = 0; i < bList.size(); i++) {
                    if (!matched[i] && deepEquals(itemA, bList.get(i))) {
                        matched[i] = true;
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
     * Creates an unmodifiable defensive snapshot view of a component map where mutable values
     * are deeply cloned so external callers cannot mutate internal snapshot state.
     * Conforms strictly to Oracle Java Map contracts.
     */
    public static Map<ComponentId, Object> defensiveMapView(Map<ComponentId, Object> internal) {
        if (internal == null || internal.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ComponentId, Object> copy = new TreeMap<>();
        for (Map.Entry<ComponentId, Object> e : internal.entrySet()) {
            copy.put(e.getKey(), deepClone(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
