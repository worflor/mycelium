package mycelium.item_presentation.v1.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An ordered guard to a model. {@code when} maps input names to conditions and holds when every one does; an empty
 * guard always holds. {@code model} is a model reference, or a template over input values.
 */
public record Case(Map<String, Condition> when, String model) {
    public Case {
        Objects.requireNonNull(model, "model");
        when = Collections.unmodifiableMap(new TreeMap<>(when));
    }

    /** Whether the guard holds for these input values. */
    public boolean matches(Map<String, Object> tuple) {
        for (Map.Entry<String, Condition> e : when.entrySet()) {
            Object value = tuple.get(e.getKey());
            if (value == null) {
                throw new IllegalArgumentException("the tuple has no value for '" + e.getKey() + "'");
            }
            if (!e.getValue().test(value)) {
                return false;
            }
        }
        return true;
    }
}
