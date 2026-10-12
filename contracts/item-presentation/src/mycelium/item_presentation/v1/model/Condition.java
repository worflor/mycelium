package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One input's part of a case's guard: equals, in, or a range. A guard is the conjunction of these over its inputs. */
public sealed interface Condition {
    /** Whether a value (a {@code Boolean}, {@code Long} or {@code String}) satisfies the condition. */
    boolean test(Object value);

    /** The condition as the program writes it. */
    Object toJson();

    /** Why this condition cannot be written against {@code domain}, or {@code null} when it can and may match. */
    String problem(Domain domain);

    /** The input equals one value. Written as the bare value. */
    record Equals(Object value) implements Condition {
        public Equals {
            if (FieldKind.of(value) == null) {
                throw new IllegalArgumentException("a guard compares a flag, a whole number or a text, not " + value);
            }
        }

        @Override
        public boolean test(Object other) {
            return value.equals(other);
        }

        @Override
        public Object toJson() {
            return value;
        }

        @Override
        public String problem(Domain domain) {
            if (FieldKind.of(value) != domain.kind()) {
                return "compares " + FieldKind.of(value).id() + " " + value + " with an input of kind " + domain.kind().id();
            }
            return domain.admits(value) ? null : "the value " + value + " is outside the input's domain, so this guard can never match";
        }
    }

    /** The input is one of several values. Written as an array. */
    record AnyOf(List<Object> values) implements Condition {
        public AnyOf {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("an 'in' guard lists at least one value");
            }
            List<Object> copy = new ArrayList<>(values);
            for (Object v : copy) {
                if (FieldKind.of(v) == null) {
                    throw new IllegalArgumentException("a guard compares a flag, a whole number or a text, not " + v);
                }
            }
            values = Collections.unmodifiableList(copy);
        }

        @Override
        public boolean test(Object other) {
            return values.contains(other);
        }

        @Override
        public Object toJson() {
            return new ArrayList<Object>(values);
        }

        @Override
        public String problem(Domain domain) {
            for (int i = 0; i < values.size(); i++) {
                String one = new Equals(values.get(i)).problem(domain);
                if (one != null) {
                    return one;
                }
                if (values.indexOf(values.get(i)) != i) {
                    return "lists " + values.get(i) + " twice";
                }
            }
            return null;
        }
    }

    /** The input is a whole number within bounds, either of which may be left open. Written as an object. */
    record Within(Long min, Long max) implements Condition {
        public Within {
            if (min == null && max == null) {
                throw new IllegalArgumentException("a range guard has a min, a max, or both");
            }
        }

        @Override
        public boolean test(Object other) {
            if (!(other instanceof Long)) {
                return false;
            }
            long v = ((Long) other).longValue();
            return (min == null || v >= min.longValue()) && (max == null || v <= max.longValue());
        }

        @Override
        public Object toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (min != null) {
                out.put("min", min);
            }
            if (max != null) {
                out.put("max", max);
            }
            return out;
        }

        @Override
        public String problem(Domain domain) {
            if (!(domain instanceof Domain.IntRange range)) {
                return "is a range, which only an int input can be compared with (this input is " + domain.kind().id() + ")";
            }
            if (min != null && max != null && min.longValue() > max.longValue()) {
                return "the range " + min + " to " + max + " is empty";
            }
            long lo = min == null ? range.min() : Math.max(min.longValue(), range.min());
            long hi = max == null ? range.max() : Math.min(max.longValue(), range.max());
            return lo > hi ? "the range matches nothing in the input's domain " + range.min() + " to " + range.max() : null;
        }
    }

    /** Reads a condition from its JSON form; a fault is added to {@code out} and the result is {@code null}. */
    static Condition read(Object json, String where, List<Diagnostic> out) {
        try {
            if (FieldKind.of(json) != null) {
                return new Equals(json);
            }
            if (json instanceof List<?> items) {
                if (items.isEmpty()) {
                    out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, where, "an 'in' guard lists at least one value"));
                    return null;
                }
                return new AnyOf(new ArrayList<Object>(items));
            }
            if (json instanceof Map<?, ?> m) {
                for (Object key : m.keySet()) {
                    if (!"min".equals(key) && !"max".equals(key)) {
                        out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, where, "a range guard has only \"min\" and \"max\", not \"" + key + "\""));
                        return null;
                    }
                }
                Object min = m.get("min");
                Object max = m.get("max");
                if ((min != null && !(min instanceof Long)) || (max != null && !(max instanceof Long))) {
                    out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, where, "range bounds are whole numbers"));
                    return null;
                }
                return new Within((Long) min, (Long) max);
            }
        } catch (IllegalArgumentException e) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, where, e.getMessage()));
            return null;
        }
        out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, where, "a guard is a value (equals), an array (in) or {\"min\": n, \"max\": n} (range)"));
        return null;
    }
}
