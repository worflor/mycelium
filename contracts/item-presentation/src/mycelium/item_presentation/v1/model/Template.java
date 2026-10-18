package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A model reference that may name input values: {@code example:item/status_tool_charged_{charge}_worn_{wear}}. A value
 * is written as a flag's {@code true} or {@code false}, a number's decimal digits, or the enum text itself.
 */
public final class Template {
    private Template() {
    }

    /** The input names a template mentions, in order of appearance (a name used twice appears twice). */
    public static List<String> placeholders(String template) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) {
                    throw new IllegalArgumentException("a '{' is never closed");
                }
                String name = template.substring(i + 1, end);
                if (!Ids.name(name)) {
                    throw new IllegalArgumentException("'{" + name + "}' does not name an input");
                }
                out.add(name);
                i = end + 1;
            } else if (c == '}') {
                throw new IllegalArgumentException("a '}' closes nothing");
            } else {
                i++;
            }
        }
        return out;
    }

    /** The model reference with each placeholder replaced by the input's value in {@code tuple}. */
    public static String expand(String template, Map<String, Object> tuple) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i);
                String name = template.substring(i + 1, end);
                Object value = tuple.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("the tuple has no value for '" + name + "'");
                }
                b.append(value);
                i = end + 1;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /**
     * Why {@code template} cannot be a model reference over these input names, or {@code null}. Every value an input can
     * take is made of characters a reference allows, so a template that is a reference with its placeholders
     * replaced by digits is one for every tuple.
     */
    public static String problem(String template, List<String> inputNames) {
        List<String> names;
        try {
            names = placeholders(template);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        for (String name : names) {
            if (!inputNames.contains(name)) {
                return "'{" + name + "}' is not a declared input";
            }
        }
        StringBuilder probe = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                probe.append('0');
                i = template.indexOf('}', i) + 1;
            } else {
                probe.append(c);
                i++;
            }
        }
        return Ids.resource(probe.toString()) ? null
                : "'" + template + "' is not a model reference (namespace:path, lower-case letters, digits and _.-/)";
    }
}
