package mycelium.item_component.v1.model;

import java.util.Objects;

/**
 * Pure algebraic representation of a component patch state:
 * <ul>
 *     <li>{@link Inherit}: The item does not modify this component; inherits prototype base state or absence.</li>
 *     <li>{@link Set}: The item explicitly sets an override value.</li>
 *     <li>{@link Remove}: The item explicitly removes this component (negative patch, e.g. {@code !key}).</li>
 * </ul>
 */
public sealed interface ComponentPatch<T> permits
        ComponentPatch.Inherit,
        ComponentPatch.Set,
        ComponentPatch.Remove {

    final class Inherit<T> implements ComponentPatch<T> {
        private static final Inherit<?> INSTANCE = new Inherit<>();

        private Inherit() {}

        @SuppressWarnings("unchecked")
        public static <T> Inherit<T> instance() {
            return (Inherit<T>) INSTANCE;
        }

        @Override
        public String toString() {
            return "Inherit";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Inherit<?>;
        }

        @Override
        public int hashCode() {
            return 31;
        }
    }

    record Set<T>(T value) implements ComponentPatch<T> {
        public Set {
            value = ValueSemantics.freeze(Objects.requireNonNull(value, "value"));
        }

        @Override
        public T value() {
            return ValueSemantics.deepClone(value);
        }
    }

    final class Remove<T> implements ComponentPatch<T> {
        private static final Remove<?> INSTANCE = new Remove<>();

        private Remove() {}

        @SuppressWarnings("unchecked")
        public static <T> Remove<T> instance() {
            return (Remove<T>) INSTANCE;
        }

        @Override
        public String toString() {
            return "Remove";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Remove<?>;
        }

        @Override
        public int hashCode() {
            return 37;
        }
    }

    static <T> Inherit<T> inherit() {
        return Inherit.instance();
    }

    static <T> Set<T> set(T value) {
        return new Set<>(value);
    }

    static <T> Remove<T> remove() {
        return Remove.instance();
    }
}
