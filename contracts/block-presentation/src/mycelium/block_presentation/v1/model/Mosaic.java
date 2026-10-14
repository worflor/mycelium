package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.Case;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A block's presentation, declared once per block: named inputs over the fields of components (the same inputs a Sigil program
 * has, read from the block entity's data instead of a stack's), and an ordered list of parts. Where a Sigil program picks one
 * model, a mosaic assembles many: every part whose guard holds is drawn, in the order they are listed. Inputs are held sorted by
 * name, so the order a mosaic lists them in changes nothing it compiles to; the order of the parts is the picture's own and is kept.
 *
 * <p>A mosaic is read through the Sigil: its inputs, guards and templates are {@code item.presentation@1}'s, parsed and checked by
 * the code that parses and checks a program, so the two cannot disagree about what a guard means.
 */
public record Mosaic(String block, List<Input> inputs, List<Part> parts) {
    /** The base a mosaic's Sigil program carries; a mosaic has none (a block with no matching part draws nothing). */
    static final String NO_BASE = "mycelium:mosaic/none";

    public Mosaic {
        Objects.requireNonNull(block, "block");
        List<Input> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(Input::name));
        inputs = Collections.unmodifiableList(sorted);
        parts = Collections.unmodifiableList(new ArrayList<>(parts));
    }

    /** The mosaic as the Sigil program it shares its inputs, guards and templates with. */
    public Program program() {
        List<Case> cases = new ArrayList<>();
        for (Part p : parts) {
            cases.add(p.asCase());
        }
        return new Program(block, NO_BASE, inputs, cases);
    }

    /** The declared inputs that some part reads, sorted by name. */
    public List<Input> readInputs() {
        return program().readInputs();
    }

    /** The same mosaic with the inputs no part reads dropped. */
    public Mosaic pruned() {
        return new Mosaic(block, readInputs(), parts);
    }

    /** The inputs part {@code n} reads (in its guard and its template), sorted by name. */
    public List<Input> inputsOf(int n) {
        List<Input> out = new ArrayList<>();
        List<String> reads = parts.get(n).reads();
        for (Input i : inputs) {
            if (reads.contains(i.name())) {
                out.add(i);
            }
        }
        return out;
    }

    /** Everything wrong with the mosaic itself, apart from what a catalog says. Empty when it is sound. */
    public List<Diagnostic> check() {
        return relabel(program().check());
    }

    // --- JSON --------------------------------------------------------------------------------------------------------

    /** The mosaic as a tree of JSON values, canonical: inputs by name, each with its default stated; parts in order. */
    public Map<String, Object> toTree() {
        Map<String, Object> shared = program().toTree();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("block", block);
        root.put("inputs", shared.get("inputs"));
        List<Object> out = new ArrayList<>();
        List<?> cases = (List<?>) shared.get("cases");
        for (int n = 0; n < parts.size(); n++) {
            Map<?, ?> c = (Map<?, ?>) cases.get(n);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("when", c.get("when"));
            one.put("model", c.get("model"));
            one.put("turn", Long.valueOf(parts.get(n).turn()));
            out.add(one);
        }
        root.put("parts", out);
        return root;
    }

    /** Reads a mosaic from JSON text, and checks it. Every reason it is refused is given. */
    public static Result<Mosaic> parse(String text) {
        Object tree;
        try {
            tree = Json.parse(text);
        } catch (Json.SyntaxException e) {
            return new Result.Failed<>(Collections.singletonList(new Diagnostic(Diagnostic.Kind.SYNTAX, "mosaic", e.getMessage())));
        }
        return fromTree(tree);
    }

    /** Reads a mosaic from a tree of JSON values, and checks it. */
    public static Result<Mosaic> fromTree(Object tree) {
        List<Diagnostic> out = new ArrayList<>();
        if (!(tree instanceof Map<?, ?> root)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "mosaic", "a mosaic is a JSON object"));
            return new Result.Failed<>(out);
        }
        unknown(root, "mosaic", Arrays.asList("block", "inputs", "parts"), out);
        if (!(root.get("parts") instanceof List<?> list)) {
            out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, "parts", "\"parts\" is an array of parts (it may be empty)"));
            return new Result.Failed<>(out);
        }
        // the Sigil reads what a Sigil program is made of; the turns are the one thing a mosaic adds
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("item", root.get("block"));
        shared.put("base", NO_BASE);
        shared.put("inputs", root.get("inputs"));
        List<Object> cases = new ArrayList<>();
        List<Integer> turns = new ArrayList<>();
        for (int n = 0; n < list.size(); n++) {
            String at = "parts[" + n + "]";
            if (!(list.get(n) instanceof Map<?, ?> part)) {
                out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "a part is an object: when, model, turn"));
                continue;
            }
            unknown(part, at, Arrays.asList("when", "model", "turn"), out);
            Object turn = part.containsKey("turn") ? part.get("turn") : Long.valueOf(0);
            if (turn instanceof Long t && Part.TURNS.contains(t.intValue()) && t.intValue() == t) {
                turns.add(t.intValue());
            } else {
                out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at + ".turn", "a turn is one of " + Part.TURNS + " degrees, not " + turn));
                turns.add(0);
            }
            Map<String, Object> c = new LinkedHashMap<>();
            if (part.containsKey("when")) {
                c.put("when", part.get("when"));
            }
            if (part.containsKey("model")) {
                c.put("model", part.get("model"));
            }
            cases.add(c);
        }
        shared.put("cases", cases);
        Result<Program> read = Program.fromTree(shared);
        if (read instanceof Result.Failed<Program> failed) {
            out.addAll(relabel(failed.diagnostics()));
        }
        if (!out.isEmpty()) {
            return new Result.Failed<>(out);
        }
        Program program = ((Result.Ok<Program>) read).value();
        List<Part> parts = new ArrayList<>();
        for (int n = 0; n < program.cases().size(); n++) {
            parts.add(new Part(program.cases().get(n).when(), program.cases().get(n).model(), turns.get(n)));
        }
        return new Result.Ok<>(new Mosaic(program.item(), program.inputs(), parts));
    }

    private static List<Diagnostic> relabel(List<Diagnostic> in) {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : in) {
            String where = d.where().equals("item") ? "block" : d.where().replace("cases[", "parts[");
            String message = d.message().replace("an item id", "a block id").replace("\"cases\"", "\"parts\"");
            out.add(new Diagnostic(d.kind(), where, message, d.lockLines()));
        }
        return out;
    }

    private static void unknown(Map<?, ?> m, String at, List<String> allowed, List<Diagnostic> out) {
        for (Object key : m.keySet()) {
            if (!allowed.contains(key)) {
                out.add(new Diagnostic(Diagnostic.Kind.SCHEMA, at, "unknown field \"" + key + "\" (this has " + allowed + ")"));
            }
        }
    }
}
