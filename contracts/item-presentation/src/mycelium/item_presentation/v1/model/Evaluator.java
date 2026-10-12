package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pure evaluation of a program: which model a tuple of input values draws. The compiler enumerates tuples with it
 * (partial evaluation), the run-time plan evaluates a stack with it, and the harness uses it as the oracle every
 * emitted artefact is held to, so the three cannot disagree.
 */
public final class Evaluator {
    /** The most tuples a program's read inputs may span (law S7), unless a compiler is built with another cap. */
    public static final int DEFAULT_MAX_TUPLES = 4096;

    private Evaluator() {
    }

    /** What reading a stack gave: each input's value, and whether every one was in its domain. */
    public record Reading(Map<String, Object> values, boolean inDomain) {
        public Reading {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** The model the first case whose guard holds names, expanded for these values; the base when none does. */
    public static String evaluate(Program program, Map<String, Object> tuple) {
        for (Case c : program.cases()) {
            if (c.matches(tuple)) {
                return Template.expand(c.model(), tuple);
            }
        }
        return program.base();
    }

    /** How many tuples these inputs span: the product of their domain sizes, saturating at {@code Long.MAX_VALUE}. */
    public static long product(List<Input> inputs) {
        long p = 1;
        for (Input i : inputs) {
            long size = i.domain().size();
            if (p > Long.MAX_VALUE / size) {
                return Long.MAX_VALUE;
            }
            p *= size;
        }
        return p;
    }

    /** Every tuple of these inputs, the last input varying fastest. The product must already be known to be small. */
    public static List<Map<String, Object>> tuples(List<Input> inputs) {
        long total = product(inputs);
        if (total > 1_000_000L) {
            throw new IllegalStateException("refusing to enumerate " + total + " tuples; the cap is checked before enumeration");
        }
        List<List<Object>> values = new ArrayList<>();
        for (Input i : inputs) {
            values.add(i.domain().values());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int[] at = new int[inputs.size()];
        for (long n = 0; n < total; n++) {
            Map<String, Object> tuple = new LinkedHashMap<>();
            for (int k = 0; k < at.length; k++) {
                tuple.put(inputs.get(k).name(), values.get(k).get(at[k]));
            }
            out.add(tuple);
            for (int k = at.length - 1; k >= 0; k--) {
                if (++at[k] < values.get(k).size()) {
                    break;
                }
                at[k] = 0;
            }
        }
        return out;
    }

    /**
     * Reads the program's read inputs from a stack. An input whose component or field is absent takes its default; a
     * value of the wrong kind or outside its domain makes the reading {@code inDomain == false}, and a plan then draws
     * the base model rather than guess.
     */
    public static Reading read(Program program, ComponentView view) {
        Map<String, Object> values = new LinkedHashMap<>();
        boolean inDomain = true;
        for (Input input : program.readInputs()) {
            Object value;
            if (input.domain() instanceof Domain.Present) {
                value = Boolean.valueOf(view.has(input.component()));
            } else {
                Optional<Object> read = view.field(input.component(), input.field());
                value = read.isPresent() ? normalize(read.get()) : input.fallback();
            }
            if (value == null || !input.domain().admits(value)) {
                inDomain = false;
                value = input.fallback();
            }
            values.put(input.name(), value);
        }
        return new Reading(values, inDomain);
    }

    /** A whole number of any width as a {@code Long}; a flag or a text as it is; anything else as {@code null}. */
    public static Object normalize(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        return null;
    }
}
