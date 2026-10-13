package mycelium.item_presentation.v1.harness;

import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Each era's rules for drawing an item, applied to the artefact a compiler emitted. They are written from the era's
 * behaviour and read nothing from the compiler: law S2 holds the emitted bytes to these, not to the emitter's own idea of
 * them.
 *
 * <ul>
 *   <li><b>Item definition (1.21.4 on).</b> A {@code select} on the {@code custom_model_data} property at string index 0
 *       draws the model of the first case whose {@code when} (a text or a list of texts) equals the stack's string, by
 *       string equality, and the fallback otherwise, also when the stack carries no string.</li>
 *   <li><b>Model overrides (1.14 to 1.21.3).</b> The stack's custom model data is a float, 0 when it has none. An override
 *       matches when that float is at least its predicate, and of those that match, the last one listed wins; none
 *       matching draws the model itself, which here is its parent.</li>
 * </ul>
 */
final class Simulators {
    private Simulators() {
    }

    /** The model an item definition draws for a stack carrying this selector, in no particular display context. */
    static String modern(String json, Selector selector) {
        return modern(json, selector, "none");
    }

    /**
     * The model an item definition draws for a stack carrying this selector, shown in this item display context: a
     * {@code select} on {@code custom_model_data} string 0 chooses by string equality (the fallback also for a stack with
     * no string), a {@code select} on {@code display_context} by the context's name, and a {@code model} is the leaf.
     */
    static String modern(String json, Selector selector, String context) {
        Map<?, ?> root = object(Json.parse(json), "the item definition");
        return resolve(object(root.get("model"), "model"), selector instanceof Selector.Key k ? k.key() : null, context);
    }

    private static String resolve(Map<?, ?> model, String string, String context) {
        Object type = model.get("type");
        if ("minecraft:model".equals(type)) {
            return (String) model.get("model");
        }
        if (!"minecraft:select".equals(type)) {
            throw new IllegalStateException("a model is neither minecraft:model nor minecraft:select: " + type);
        }
        Object cases = model.get("cases");
        if (!(cases instanceof List<?> list)) {
            throw new IllegalStateException("the select has no cases array");
        }
        String chosen;
        if ("minecraft:custom_model_data".equals(model.get("property"))) {
            if (!Long.valueOf(0).equals(model.get("index"))) {
                throw new IllegalStateException("the select is not on custom_model_data string index 0");
            }
            chosen = string;
        } else if ("minecraft:display_context".equals(model.get("property"))) {
            chosen = context;
        } else {
            throw new IllegalStateException("the select is on " + model.get("property") + ", which the era does not draw by here");
        }
        if (chosen != null) {
            for (Object c : list) {
                Map<?, ?> one = object(c, "a case");
                Object when = one.get("when");
                boolean hit = when instanceof String ? when.equals(chosen) : when instanceof List<?> w && w.contains(chosen);
                if (hit) {
                    return resolve(object(one.get("model"), "a case's model"), string, context);
                }
            }
        }
        return resolve(object(model.get("fallback"), "fallback"), string, context);
    }

    /** The model an item model with overrides draws for a stack carrying this selector. */
    static String legacy(String json, Selector selector) {
        Map<?, ?> root = object(Json.parse(json), "the item model");
        long value = selector instanceof Selector.Code c ? c.code() : 0;
        String drawn = (String) root.get("parent");
        Object overrides = root.get("overrides");
        if (!(overrides instanceof List<?> list) || drawn == null) {
            throw new IllegalStateException("the item model has no parent and overrides");
        }
        for (Object o : list) {
            Map<?, ?> one = object(o, "an override");
            Map<?, ?> predicate = object(one.get("predicate"), "a predicate");
            Object threshold = predicate.get("custom_model_data");
            if (!(threshold instanceof Long)) {
                throw new IllegalStateException("an override's predicate is not a custom_model_data number");
            }
            if (value >= ((Long) threshold).longValue()) {
                drawn = (String) one.get("model");
            }
        }
        return drawn;
    }

    /** The predicates of a model's overrides, in the order written. */
    static List<Long> predicates(String json) {
        List<Long> out = new ArrayList<>();
        for (Object o : (List<?>) object(Json.parse(json), "the item model").get("overrides")) {
            out.add((Long) object(object(o, "an override").get("predicate"), "a predicate").get("custom_model_data"));
        }
        return out;
    }

    /** The keys of a definition's cases, in the order written. */
    static List<String> caseKeys(String json) {
        List<String> out = new ArrayList<>();
        Map<?, ?> model = object(object(Json.parse(json), "the item definition").get("model"), "model");
        Object cases = model.get("cases");
        if (cases instanceof List<?> list) {
            for (Object c : list) {
                out.add((String) object(c, "a case").get("when"));
            }
        }
        return out;
    }

    private static Map<?, ?> object(Object v, String what) {
        if (v instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalStateException(what + " is not a JSON object");
    }
}
