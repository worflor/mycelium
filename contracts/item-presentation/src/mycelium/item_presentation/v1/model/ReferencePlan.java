package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The plan the reference compiler builds, and the one an adapter reads back from the resource it was written to. */
public final class ReferencePlan implements Plan {
    private final Program program;
    private final Realization realization;
    private final Map<String, Integer> codes;

    /** @param program already pruned to its read inputs; @param codes each live selection's key to its code */
    public ReferencePlan(Program program, Realization realization, Map<String, Integer> codes) {
        if (realization == Realization.NONE) {
            throw new IllegalArgumentException("there is no plan for a version without a presentation mechanism");
        }
        this.program = program;
        this.realization = realization;
        this.codes = Collections.unmodifiableMap(new TreeMap<>(codes));
    }

    @Override
    public String item() {
        return program.item();
    }

    @Override
    public Realization realization() {
        return realization;
    }

    @Override
    public String base() {
        return program.base();
    }

    @Override
    public Program program() {
        return program;
    }

    @Override
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : codes.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue().intValue()));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public Selection select(ComponentView view) {
        Evaluator.Reading reading = Evaluator.read(program, view);
        if (!reading.inDomain()) {
            return Selection.base(program.base(), false);
        }
        return select(reading.values());
    }

    @Override
    public Selection select(Map<String, Object> tuple) {
        String model = Evaluator.evaluate(program, tuple);
        if (model.equals(program.base())) {
            return Selection.base(model, true);
        }
        Integer code = codes.get(model);
        if (code == null) {
            throw new IllegalStateException("the plan for " + program.item() + " has no selection for " + model);
        }
        return new Selection(model, model, code.intValue(), true);
    }

    @Override
    public Selector selectorOf(Selection selection) {
        if (selection.isBase()) {
            return new Selector.None();
        }
        return realization == Realization.ITEM_DEFINITION ? new Selector.Key(selection.key()) : new Selector.Code(selection.code());
    }

    @Override
    public Map<String, Object> toTree() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", Long.valueOf(1));
        root.put("realization", realization.id());
        root.put("program", program.toTree());
        List<Object> selections = new ArrayList<>();
        for (Map.Entry<String, Integer> e : codes.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("key", e.getKey());
            one.put("code", Long.valueOf(e.getValue().intValue()));
            selections.add(one);
        }
        root.put("selections", selections);
        return root;
    }

    /**
     * Reads a plan from the resource the compiler wrote. The selections must cover everything the program can draw, so
     * an adapter never meets a model with no key.
     */
    public static Result<Plan> parse(String text) {
        Object tree;
        try {
            tree = Json.parse(text);
        } catch (Json.SyntaxException e) {
            return fail(Diagnostic.Kind.SYNTAX, e.getMessage());
        }
        if (!(tree instanceof Map<?, ?> root) || root.size() != 4 || !Long.valueOf(1).equals(root.get("schema"))
                || !(root.get("realization") instanceof String) || !(root.get("selections") instanceof List<?> selections)) {
            return fail(Diagnostic.Kind.SCHEMA, "a plan is {\"schema\": 1, \"realization\", \"program\", \"selections\"}");
        }
        Realization realization;
        try {
            realization = Realization.parse((String) root.get("realization"));
        } catch (IllegalArgumentException e) {
            return fail(Diagnostic.Kind.SCHEMA, e.getMessage());
        }
        Result<Program> program = Program.fromTree(root.get("program"));
        if (program instanceof Result.Failed<Program> failed) {
            return new Result.Failed<>(failed.diagnostics());
        }
        Program p = ((Result.Ok<Program>) program).value();
        Map<String, Integer> codes = new TreeMap<>();
        for (Object s : selections) {
            if (!(s instanceof Map<?, ?> one) || one.size() != 2 || !(one.get("key") instanceof String) || !(one.get("code") instanceof Long)) {
                return fail(Diagnostic.Kind.SCHEMA, "a selection is {\"key\": \"<model>\", \"code\": n}");
            }
            long code = ((Long) one.get("code")).longValue();
            if (code < 1 || code > Lock.MAX_CODE || codes.put((String) one.get("key"), Integer.valueOf((int) code)) != null) {
                return fail(Diagnostic.Kind.SCHEMA, "a selection's code is 1 to " + Lock.MAX_CODE + ", and each key appears once");
            }
        }
        if (Evaluator.product(p.inputs()) > Evaluator.DEFAULT_MAX_TUPLES) {
            return fail(Diagnostic.Kind.TOO_LARGE, "the plan spans more than " + Evaluator.DEFAULT_MAX_TUPLES + " tuples");
        }
        ReferencePlan plan = new ReferencePlan(p, realization, codes);
        for (Map<String, Object> tuple : Evaluator.tuples(p.inputs())) {
            String model = Evaluator.evaluate(p, tuple);
            if (!model.equals(p.base()) && !codes.containsKey(model)) {
                return fail(Diagnostic.Kind.SCHEMA, "the program can draw " + model + ", which the plan has no selection for");
            }
        }
        return new Result.Ok<>(plan);
    }

    private static Result<Plan> fail(Diagnostic.Kind kind, String message) {
        return new Result.Failed<>(Collections.singletonList(new Diagnostic(kind, "plan", message)));
    }
}
