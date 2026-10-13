package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The reference compiler, which keeps every law. Its steps are the contract's: check the program and the projection (S5),
 * bound the domain product before enumerating (S7), partially evaluate into selections, take their codes from the lock
 * (S3), and emit the target's artefact deterministically (S4, S6) beside the plan that agrees with it (S1, S2).
 *
 * <p>The steps a broken compiler gets wrong are protected methods, so the rejection matrix breaks exactly one of them
 * and leaves the rest as they are.
 */
public class ReferenceSigil implements SigilCompiler {
    private final int maxTuples;

    public ReferenceSigil() {
        this(Evaluator.DEFAULT_MAX_TUPLES);
    }

    public ReferenceSigil(int maxTuples) {
        if (maxTuples < 1) {
            throw new IllegalArgumentException("the cap is at least one tuple");
        }
        this.maxTuples = maxTuples;
    }

    @Override
    public int maxTuples() {
        return maxTuples;
    }

    @Override
    public Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target) {
        String item = program.item();
        if (target.realization() == Realization.NONE) {
            return reject(new Diagnostic(Diagnostic.Kind.NO_REALIZATION, item, "Minecraft " + target.minecraft()
                    + " has no item presentation mechanism (the era table gives it \"none\"): there is nothing to compile for"));
        }
        List<Diagnostic> found = new ArrayList<>(program.check());
        if (!found.isEmpty()) {
            return new Compilation.Rejected(found);
        }
        found.addAll(projectionDiagnostics(program, catalog));
        if (!found.isEmpty()) {
            return new Compilation.Rejected(found);
        }
        Program pruned = program.pruned();
        long span = Evaluator.product(pruned.inputs());
        if (span > maxTuples) {
            return reject(new Diagnostic(Diagnostic.Kind.TOO_LARGE, item, "the inputs the cases read span "
                    + (span == Long.MAX_VALUE ? "more than 2^63" : String.valueOf(span)) + " tuples; the cap is " + maxTuples
                    + ". Narrow a domain, or split the item's state"));
        }
        TreeSet<String> keys = new TreeSet<>();
        for (Map<String, Object> tuple : Evaluator.tuples(pruned.inputs())) {
            keys.add(Evaluator.evaluate(pruned, tuple));
        }
        keys.remove(program.base());
        if (target.realization() == Realization.MODEL_OVERRIDES) {
            String own = Layout.itemModel(item);
            List<String> drawn = new ArrayList<>(keys);
            drawn.add(program.base());
            if (drawn.contains(own)) {
                return reject(new Diagnostic(Diagnostic.Kind.MODEL_CLASH, item, "Minecraft " + target.minecraft()
                        + " draws overrides on the item's own model " + own + ", which a program also names as a model to draw: it would be its own parent"
                        + ". Give the program's models other names"));
            }
        }
        List<String> drawable = new ArrayList<>(keys);
        drawable.add(program.base());
        for (Context rule : program.contexts()) {
            if (!drawable.contains(rule.model())) {
                found.add(new Diagnostic(Diagnostic.Kind.CONTEXT, "contexts", "the rule for " + rule.model()
                        + " names a model the program never draws (it draws " + drawable + ")"));
            }
        }
        if (!found.isEmpty()) {
            return new Compilation.Rejected(found);
        }
        Map<String, Integer> codes = assignCodes(item, new ArrayList<>(keys), lock, found);
        if (!found.isEmpty() || codes == null) {
            return new Compilation.Rejected(found);
        }
        Plan plan = newPlan(pruned, target.realization(), codes);
        Map<String, String> artefacts = new TreeMap<>();
        if (target.realization() == Realization.ITEM_DEFINITION) {
            artefacts.put(Layout.definition(item), render(itemDefinition(program.base(), new ArrayList<>(keys), program.contexts())));
        } else {
            artefacts.put(Layout.model(item), render(modelOverrides(program.base(), codes, lock.entries(item))));
        }
        artefacts.put(Layout.plan(item), render(plan.toTree()));
        return new Compilation.Compiled(plan, artefacts);
    }

    private static Compilation reject(Diagnostic d) {
        return new Compilation.Rejected(Collections.singletonList(d));
    }

    // --- the steps a broken compiler can get wrong -----------------------------------------------------------------

    /** Every reason the projection (S5) refuses the program: an input over a component the client cannot see, and kin. */
    protected List<Diagnostic> projectionDiagnostics(Program program, ComponentCatalog catalog) {
        List<Diagnostic> out = new ArrayList<>();
        for (Input input : program.inputs()) {
            String at = "inputs." + input.name();
            ComponentCatalog.Component component = catalog.component(input.component());
            if (component == null) {
                out.add(new Diagnostic(Diagnostic.Kind.CATALOG, at, "the component " + input.component() + " is not in the catalog"));
                continue;
            }
            out.addAll(visibility(input, component));
            if (input.domain() instanceof Domain.Present) {
                if (!component.unit()) {
                    out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, "a 'present' input reads a unit component, and "
                            + component.id() + " is a record"));
                }
            } else if (component.unit()) {
                out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, component.id() + " is a unit component: it has no field '"
                        + input.field() + "' (use a 'present' domain)"));
            } else if (input.field().isEmpty()) {
                if (component.scalar() == null) {
                    out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, component.id() + " is a record: the input needs the path of the field it reads"));
                } else if (component.scalar() != input.domain().kind()) {
                    out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, component.id() + " is a scalar " + component.scalar().id()
                            + ", and the input's domain is " + input.domain().kind().id()));
                }
            } else if (component.scalar() != null) {
                out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, component.id() + " is a scalar: it has no field '" + input.field()
                        + "' (leave the field out)"));
            } else {
                FieldKind kind = component.fields().get(input.field());
                if (kind == null) {
                    out.add(new Diagnostic(Diagnostic.Kind.CATALOG, at, component.id() + " has no field '" + input.field()
                            + "' (it has " + component.fields().keySet() + ")"));
                } else if (kind != input.domain().kind()) {
                    out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, "the field '" + input.field() + "' of " + component.id()
                            + " is " + kind.id() + ", and the input's domain is " + input.domain().kind().id()));
                }
            }
        }
        return out;
    }

    /** The projection law itself (S5): a component the client is not sent cannot decide what the client draws. */
    protected List<Diagnostic> visibility(Input input, ComponentCatalog.Component component) {
        if (component.networkSynced()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new Diagnostic(Diagnostic.Kind.SERVER_PRIVATE, "inputs." + input.name(),
                "reads " + component.id() + ", which is not network-visible: the client never receives it, so presentation cannot"
                        + " depend on it. Make the component network-synced, or read something the client has"));
    }

    /**
     * The code of every selection, taken from the lock (S3). A selection the lock lacks is a diagnostic naming the exact
     * lines to add, and the result is {@code null}: the build never invents a code a committed file did not record.
     */
    protected Map<String, Integer> assignCodes(String item, List<String> keys, Lock lock, List<Diagnostic> found) {
        Map<String, Integer> codes = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            Integer code = lock.codeOf(item, key);
            if (code == null) {
                missing.add(key);
            } else {
                codes.put(key, code);
            }
        }
        if (missing.isEmpty()) {
            return codes;
        }
        List<Lock.Entry> add;
        try {
            add = lock.allocate(item, missing);
        } catch (IllegalStateException full) {
            found.add(new Diagnostic(Diagnostic.Kind.LOCK_INVALID, "lock", full.getMessage()));
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (Lock.Entry e : add) {
            lines.add(Lock.line(e));
        }
        found.add(new Diagnostic(Diagnostic.Kind.LOCK_MISSING, "lock", "the lock has no code for " + missing.size() + " selection"
                + (missing.size() == 1 ? "" : "s") + " of " + item + ". Add these lines to its entries:\n  " + String.join("\n  ", lines),
                lines));
        return null;
    }

    /** The plan the adapter evaluates. */
    protected Plan newPlan(Program pruned, Realization realization, Map<String, Integer> codes) {
        return new ReferencePlan(pruned, realization, codes);
    }

    /** The legacy overrides in the order they are written (S4: ascending by code, which the era's rules need). */
    protected List<Plan.Entry> overrideOrder(List<Plan.Entry> rows) {
        List<Plan.Entry> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Integer.compare(a.code(), b.code()));
        return sorted;
    }

    /** A tree of values as bytes (S6): canonical JSON. */
    protected String render(Object tree) {
        return Json.write(tree);
    }

    // --- the artefacts ---------------------------------------------------------------------------------------------

    /** {@code items/<id>.json}: a select on the first custom model data string, a case per selection, the base as fallback. */
    protected Map<String, Object> itemDefinition(String base, List<String> keys, List<Context> contexts) {
        Map<String, Object> model;
        if (keys.isEmpty()) {
            model = drawn(base, contexts);
        } else {
            model = new LinkedHashMap<>();
            model.put("type", "minecraft:select");
            model.put("property", "minecraft:custom_model_data");
            model.put("index", Long.valueOf(0));
            List<Object> cases = new ArrayList<>();
            for (String key : keys) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("when", key);
                one.put("model", drawn(key, contexts));
                cases.add(one);
            }
            model.put("cases", cases);
            model.put("fallback", drawn(base, contexts));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        return root;
    }

    /** A model, or the select on the display context the program's rule for it makes (S8). */
    protected Map<String, Object> drawn(String ref, List<Context> contexts) {
        Context rule = null;
        for (Context c : contexts) {
            if (c.model().equals(ref)) {
                rule = c;
            }
        }
        if (rule == null) {
            return plainModel(ref);
        }
        Map<String, Object> select = new LinkedHashMap<>();
        select.put("type", "minecraft:select");
        select.put("property", "minecraft:display_context");
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("when", new ArrayList<Object>(rule.names()));
        one.put("model", plainModel(rule.except() ? rule.model() : rule.draw()));
        List<Object> cases = new ArrayList<>();
        cases.add(one);
        select.put("cases", cases);
        select.put("fallback", plainModel(rule.except() ? rule.draw() : rule.model()));
        return select;
    }

    private static Map<String, Object> plainModel(String ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "minecraft:model");
        m.put("model", ref);
        return m;
    }

    /**
     * {@code models/item/<id>.json}: the base model as parent and an override per code. A code the program no longer
     * produces keeps its row, drawing the base, so a stack still carrying it is not drawn as its neighbour.
     */
    protected Map<String, Object> modelOverrides(String base, Map<String, Integer> codes, List<Lock.Entry> locked) {
        List<Plan.Entry> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : codes.entrySet()) {
            rows.add(new Plan.Entry(e.getKey(), e.getValue().intValue()));
        }
        for (Lock.Entry e : locked) {
            if (!codes.containsKey(e.key())) {
                rows.add(new Plan.Entry(e.key(), e.code()));
            }
        }
        List<Object> overrides = new ArrayList<>();
        for (Plan.Entry row : overrideOrder(rows)) {
            Map<String, Object> predicate = new LinkedHashMap<>();
            predicate.put("custom_model_data", Long.valueOf(row.code()));
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("model", codes.containsKey(row.key()) ? row.key() : base);
            one.put("predicate", predicate);
            overrides.add(one);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("parent", base);
        root.put("overrides", overrides);
        return root;
    }
}
