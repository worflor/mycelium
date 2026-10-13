package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A presentation program, declared once per item: named inputs over the fields of components, ordered cases, and the
 * model drawn when no case applies. The first case whose guard holds wins. Inputs are held sorted by name, so the
 * order a program lists them in changes nothing it compiles to. {@code contexts} are the optional display context rules
 * ({@link Context}): where the item definition draws another model than the one a stack selected; held sorted by model.
 */
public record Program(String item, String base, List<Input> inputs, List<Case> cases, List<Context> contexts) {
    public Program {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(base, "base");
        List<Input> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(Input::name));
        inputs = Collections.unmodifiableList(sorted);
        cases = Collections.unmodifiableList(new ArrayList<>(cases));
        List<Context> rules = new ArrayList<>(contexts);
        rules.sort(Comparator.comparing(Context::model));
        contexts = Collections.unmodifiableList(rules);
    }

    /** A program with no display context rules. */
    public Program(String item, String base, List<Input> inputs, List<Case> cases) {
        this(item, base, inputs, cases, Collections.<Context>emptyList());
    }

    /** The rule for a model, or {@code null}. */
    public Context contextFor(String model) {
        for (Context c : contexts) {
            if (c.model().equals(model)) {
                return c;
            }
        }
        return null;
    }

    /** The input called {@code name}, or {@code null}. */
    public Input input(String name) {
        for (Input i : inputs) {
            if (i.name().equals(name)) {
                return i;
            }
        }
        return null;
    }

    /** The names of the inputs some case reads, in a guard or in a template: the others change nothing. */
    public List<String> readNames() {
        TreeSet<String> names = new TreeSet<>();
        for (Case c : cases) {
            names.addAll(c.when().keySet());
            try {
                names.addAll(Template.placeholders(c.model()));
            } catch (IllegalArgumentException malformed) {
                // reported by check()
            }
        }
        return new ArrayList<>(names);
    }

    /** The declared inputs that some case reads, sorted by name. */
    public List<Input> readInputs() {
        List<String> names = readNames();
        List<Input> out = new ArrayList<>();
        for (Input i : inputs) {
            if (names.contains(i.name())) {
                out.add(i);
            }
        }
        return out;
    }

    /** The same program with the inputs no case reads dropped. */
    public Program pruned() {
        return new Program(item, base, readInputs(), cases, contexts);
    }

    // --- checking ----------------------------------------------------------------------------------------------------

    /** Everything wrong with the program itself, apart from what a catalog or a lock says. Empty when it is sound. */
    public List<Diagnostic> check() {
        List<Diagnostic> out = new ArrayList<>();
        if (!Ids.resource(item)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "item", "'" + item + "' is not an item id (namespace:path)"));
        }
        if (!Ids.resource(base)) {
            out.add(new Diagnostic(Diagnostic.Kind.TEMPLATE, "base", "'" + base + "' is not a model reference (namespace:path)"));
        }
        List<String> names = new ArrayList<>();
        for (Input i : inputs) {
            if (names.contains(i.name())) {
                out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "inputs." + i.name(), "the input is declared twice"));
            }
            names.add(i.name());
        }
        for (int n = 0; n < cases.size(); n++) {
            Case c = cases.get(n);
            String at = "cases[" + n + "]";
            for (Map.Entry<String, Condition> g : c.when().entrySet()) {
                Input input = input(g.getKey());
                if (input == null) {
                    out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at + ".when." + g.getKey(), "guards an input that is not declared"));
                    continue;
                }
                String problem = g.getValue().problem(input.domain());
                if (problem != null) {
                    out.add(new Diagnostic(problem.contains("compares") || problem.contains("only an int")
                            ? Diagnostic.Kind.TYPE : Diagnostic.Kind.DOMAIN, at + ".when." + g.getKey(), problem));
                }
            }
            String template = Template.problem(c.model(), names);
            if (template != null) {
                out.add(new Diagnostic(Diagnostic.Kind.TEMPLATE, at + ".model", template));
            }
        }
        List<String> ruled = new ArrayList<>();
        for (int n = 0; n < contexts.size(); n++) {
            Context rule = contexts.get(n);
            String at = "contexts[" + n + "]";
            out.addAll(rule.check(at));
            if (ruled.contains(rule.model())) {
                out.add(new Diagnostic(Diagnostic.Kind.CONTEXT, at, "two rules for " + rule.model() + ": a model has at most one"));
            }
            ruled.add(rule.model());
        }
        return out;
    }

    // --- JSON --------------------------------------------------------------------------------------------------------

    /** The program as a tree of JSON values, canonical: inputs by name, each with its default stated. */
    public Map<String, Object> toTree() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("item", item);
        root.put("base", base);
        Map<String, Object> ins = new LinkedHashMap<>();
        for (Input i : inputs) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("component", i.component());
            if (!i.field().isEmpty()) {
                one.put("field", i.field());
            }
            one.put("domain", i.domain().toJson());
            one.put("default", i.fallback());
            ins.put(i.name(), one);
        }
        root.put("inputs", ins);
        List<Object> cs = new ArrayList<>();
        for (Case c : cases) {
            Map<String, Object> one = new LinkedHashMap<>();
            Map<String, Object> when = new LinkedHashMap<>();
            for (Map.Entry<String, Condition> g : c.when().entrySet()) {
                when.put(g.getKey(), g.getValue().toJson());
            }
            one.put("when", when);
            one.put("model", c.model());
            cs.add(one);
        }
        root.put("cases", cs);
        if (!contexts.isEmpty()) {
            List<Object> rules = new ArrayList<>();
            for (Context c : contexts) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put(c.except() ? "except" : "only", new ArrayList<Object>(c.names()));
                one.put("model", c.model());
                one.put("draw", c.draw());
                rules.add(one);
            }
            root.put("contexts", rules);
        }
        return root;
    }

    /** Reads a program from JSON text, and checks it. Every reason it is refused is given. */
    public static Result<Program> parse(String text) {
        Object tree;
        try {
            tree = Json.parse(text);
        } catch (Json.SyntaxException e) {
            return new Result.Failed<>(Collections.singletonList(new Diagnostic(Diagnostic.Kind.SYNTAX, "program", e.getMessage())));
        }
        return fromTree(tree);
    }

    /** Reads a program from a tree of JSON values, and checks it. */
    public static Result<Program> fromTree(Object tree) {
        List<Diagnostic> out = new ArrayList<>();
        Program program = read(tree, out);
        if (program != null) {
            out.addAll(program.check());
        }
        if (!out.isEmpty()) {
            return new Result.Failed<>(out);
        }
        return new Result.Ok<>(program);
    }

    private static Program read(Object tree, List<Diagnostic> out) {
        if (!(tree instanceof Map<?, ?> root)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "program", "a program is a JSON object"));
            return null;
        }
        int before = out.size();
        unknown(root, "program", Arrays.asList("item", "base", "inputs", "cases", "contexts"), out);
        String item = text(root, "item", "item", out);
        String base = text(root, "base", "base", out);
        List<Input> inputs = new ArrayList<>();
        Object ins = root.get("inputs");
        if (!(ins instanceof Map<?, ?> inMap)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "inputs", "\"inputs\" is an object of named inputs (it may be empty)"));
        } else {
            for (Map.Entry<?, ?> e : inMap.entrySet()) {
                Input input = readInput((String) e.getKey(), e.getValue(), out);
                if (input != null) {
                    inputs.add(input);
                }
            }
        }
        List<Case> cases = new ArrayList<>();
        Object cs = root.get("cases");
        if (!(cs instanceof List<?> list)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "cases", "\"cases\" is an array of cases (it may be empty)"));
        } else {
            for (int n = 0; n < list.size(); n++) {
                Case c = readCase(list.get(n), "cases[" + n + "]", out);
                if (c != null) {
                    cases.add(c);
                }
            }
        }
        List<Context> contexts = new ArrayList<>();
        Object ctx = root.get("contexts");
        if (ctx != null && !(ctx instanceof List<?>)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "contexts", "\"contexts\" is an array of display context rules"));
        } else if (ctx != null) {
            List<?> list = (List<?>) ctx;
            for (int n = 0; n < list.size(); n++) {
                Context c = readContext(list.get(n), "contexts[" + n + "]", out);
                if (c != null) {
                    contexts.add(c);
                }
            }
        }
        if (out.size() > before || item == null || base == null) {
            return null;
        }
        return new Program(item, base, inputs, cases, contexts);
    }

    private static Input readInput(String name, Object json, List<Diagnostic> out) {
        String at = "inputs." + name;
        if (!Ids.name(name)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "'" + name + "' is not an input name (lower-case letters, digits, '_', starting with a letter)"));
            return null;
        }
        if (!(json instanceof Map<?, ?> m)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "an input is an object: component, field, domain, default"));
            return null;
        }
        int before = out.size();
        unknown(m, at, Arrays.asList("component", "field", "domain", "default"), out);
        String component = text(m, "component", at + ".component", out);
        Domain domain = m.containsKey("domain") ? Domain.read(m.get("domain"), at + ".domain", out) : null;
        if (!m.containsKey("domain")) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at + ".domain", "the input needs a domain"));
        }
        String field = m.containsKey("field") ? text(m, "field", at + ".field", out) : "";
        Object fallback = m.get("default");
        if (out.size() > before || component == null || domain == null || field == null) {
            return null;
        }
        try {
            return new Input(name, component, field, domain, fallback);
        } catch (IllegalArgumentException e) {
            out.add(new Diagnostic(fallback != null && e.getMessage().contains("default") ? Diagnostic.Kind.DOMAIN : Diagnostic.Kind.SCHEMA,
                    at, e.getMessage()));
            return null;
        }
    }

    private static Context readContext(Object json, String at, List<Diagnostic> out) {
        if (!(json instanceof Map<?, ?> m)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "a display context rule is an object: only or except, model, draw"));
            return null;
        }
        int before = out.size();
        unknown(m, at, Arrays.asList("only", "except", "model", "draw"), out);
        String model = text(m, "model", at + ".model", out);
        String draw = text(m, "draw", at + ".draw", out);
        if (m.containsKey("only") == m.containsKey("except")) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "a rule lists the contexts it applies in (\"only\") or the ones it does not (\"except\"), one of them"));
            return null;
        }
        boolean except = m.containsKey("except");
        Object listed = m.get(except ? "except" : "only");
        List<String> names = new ArrayList<>();
        if (!(listed instanceof List<?> l)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "\"" + (except ? "except" : "only") + "\" is an array of display context names"));
            return null;
        }
        for (Object o : l) {
            if (!(o instanceof String)) {
                out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "a display context name is a text"));
                return null;
            }
            names.add((String) o);
        }
        if (out.size() > before || model == null || draw == null) {
            return null;
        }
        return new Context(except, names, model, draw);
    }

    private static Case readCase(Object json, String at, List<Diagnostic> out) {
        if (!(json instanceof Map<?, ?> m)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "a case is an object: when, model"));
            return null;
        }
        int before = out.size();
        unknown(m, at, Arrays.asList("when", "model"), out);
        String model = text(m, "model", at + ".model", out);
        Map<String, Condition> when = new LinkedHashMap<>();
        Object w = m.get("when");
        if (w != null && !(w instanceof Map<?, ?>)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at + ".when", "\"when\" is an object of guards, one per input"));
        } else if (w != null) {
            for (Map.Entry<?, ?> g : ((Map<?, ?>) w).entrySet()) {
                Condition c = Condition.read(g.getValue(), at + ".when." + g.getKey(), out);
                if (c != null) {
                    when.put((String) g.getKey(), c);
                }
            }
        }
        if (out.size() > before || model == null) {
            return null;
        }
        return new Case(when, model);
    }

    private static void unknown(Map<?, ?> m, String at, List<String> allowed, List<Diagnostic> out) {
        for (Object key : m.keySet()) {
            if (!allowed.contains(key)) {
                out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "unknown field \"" + key + "\" (this has " + allowed + ")"));
            }
        }
    }

    private static String text(Map<?, ?> m, String key, String at, List<Diagnostic> out) {
        Object v = m.get(key);
        if (v instanceof String) {
            return (String) v;
        }
        out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "\"" + key + "\" is required, and is a text"));
        return null;
    }
}
