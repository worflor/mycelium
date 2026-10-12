package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The finite set of values an input can take. Presentation enumerates it, so it is finite by construction: a flag, the
 * presence of a unit component, a whole-number range, or a listed set of texts.
 */
public sealed interface Domain {
    /** The largest magnitude of a range bound. */
    long LIMIT = 1L << 31;

    /** How many values (at most 2^32, so a product of a few sizes is the only thing that can overflow). */
    long size();

    /** Every value in order. Only for a domain already known to be small. */
    List<Object> values();

    /** Whether {@code value} (a {@code Boolean}, {@code Long} or {@code String}) is in the domain. */
    boolean admits(Object value);

    /** What an input falls back to when its component or field is absent: the first value. */
    Object first();

    /** The kind of value a guard on this domain compares. */
    FieldKind kind();

    /** The domain as the program writes it. */
    Object toJson();

    /** {@code true} or {@code false}, read from a boolean field. */
    record Flag() implements Domain {
        @Override
        public long size() {
            return 2;
        }

        @Override
        public List<Object> values() {
            List<Object> out = new ArrayList<>();
            out.add(Boolean.FALSE);
            out.add(Boolean.TRUE);
            return out;
        }

        @Override
        public boolean admits(Object value) {
            return value instanceof Boolean;
        }

        @Override
        public Object first() {
            return Boolean.FALSE;
        }

        @Override
        public FieldKind kind() {
            return FieldKind.BOOL;
        }

        @Override
        public Object toJson() {
            return "bool";
        }
    }

    /** Whether a unit component is on the stack. */
    record Present() implements Domain {
        @Override
        public long size() {
            return 2;
        }

        @Override
        public List<Object> values() {
            return new Flag().values();
        }

        @Override
        public boolean admits(Object value) {
            return value instanceof Boolean;
        }

        @Override
        public Object first() {
            return Boolean.FALSE;
        }

        @Override
        public FieldKind kind() {
            return FieldKind.BOOL;
        }

        @Override
        public Object toJson() {
            return "present";
        }
    }

    /** The whole numbers from {@code min} to {@code max}, both included. */
    record IntRange(long min, long max) implements Domain {
        public IntRange {
            if (min > max) {
                throw new IllegalArgumentException("an int domain runs from its smaller bound to its larger: " + min + " to " + max);
            }
            if (min < -LIMIT || max > LIMIT) {
                throw new IllegalArgumentException("an int domain's bounds stay within +/-2^31: " + min + " to " + max);
            }
        }

        @Override
        public long size() {
            return max - min + 1;
        }

        @Override
        public List<Object> values() {
            if (size() > 1_000_000L) {
                throw new IllegalStateException("refusing to list " + size() + " values; the cap is checked before enumeration");
            }
            List<Object> out = new ArrayList<>();
            for (long v = min; v <= max; v++) {
                out.add(Long.valueOf(v));
            }
            return out;
        }

        @Override
        public boolean admits(Object value) {
            return value instanceof Long && ((Long) value).longValue() >= min && ((Long) value).longValue() <= max;
        }

        @Override
        public Object first() {
            return Long.valueOf(min);
        }

        @Override
        public FieldKind kind() {
            return FieldKind.INT;
        }

        @Override
        public Object toJson() {
            List<Object> bounds = new ArrayList<>();
            bounds.add(Long.valueOf(min));
            bounds.add(Long.valueOf(max));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("int", bounds);
            return out;
        }
    }

    /** A listed set of texts, each usable inside a model reference. */
    record Choice(List<String> choices) implements Domain {
        public Choice {
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("an enum domain lists at least one value");
            }
            List<String> copy = new ArrayList<>(choices);
            for (int i = 0; i < copy.size(); i++) {
                if (!Ids.choice(copy.get(i))) {
                    throw new IllegalArgumentException("'" + copy.get(i) + "' is not an enum value (lower-case letters, digits and '_')");
                }
                if (copy.indexOf(copy.get(i)) != i) {
                    throw new IllegalArgumentException("the enum value '" + copy.get(i) + "' is listed twice");
                }
            }
            choices = Collections.unmodifiableList(copy);
        }

        @Override
        public long size() {
            return choices.size();
        }

        @Override
        public List<Object> values() {
            return new ArrayList<Object>(choices);
        }

        @Override
        public boolean admits(Object value) {
            return value instanceof String && choices.contains(value);
        }

        @Override
        public Object first() {
            return choices.get(0);
        }

        @Override
        public FieldKind kind() {
            return FieldKind.TEXT;
        }

        @Override
        public Object toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enum", new ArrayList<Object>(choices));
            return out;
        }
    }

    /**
     * Reads a domain from its JSON form ({@code "bool"}, {@code "present"}, {@code {"int": [min, max]}} or
     * {@code {"enum": [...]}}); a fault is added to {@code out} and the result is {@code null}.
     */
    static Domain read(Object json, String where, List<Diagnostic> out) {
        if ("bool".equals(json)) {
            return new Flag();
        }
        if ("present".equals(json)) {
            return new Present();
        }
        if (json instanceof Map<?, ?> m && m.size() == 1) {
            Object ints = m.get("int");
            Object texts = m.get("enum");
            try {
                if (ints instanceof List<?> bounds && bounds.size() == 2 && bounds.get(0) instanceof Long && bounds.get(1) instanceof Long) {
                    return new IntRange((Long) bounds.get(0), (Long) bounds.get(1));
                }
                if (texts instanceof List<?> items) {
                    List<String> values = new ArrayList<>();
                    for (Object item : items) {
                        if (!(item instanceof String)) {
                            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, where, "an enum lists texts, not " + item));
                            return null;
                        }
                        values.add((String) item);
                    }
                    return new Choice(values);
                }
            } catch (IllegalArgumentException e) {
                out.add(new Diagnostic(Diagnostic.Kind.DOMAIN, where, e.getMessage()));
                return null;
            }
        }
        out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, where,
                "a domain is \"bool\", \"present\", {\"int\": [min, max]} or {\"enum\": [\"a\", \"b\"]}"));
        return null;
    }
}
