package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Where a model is drawn: a rule that draws another model for one the program can draw, depending on the item display
 * context (the hand, the gui, the ground, an item display entity). It exists because an item definition can ask the
 * context and an item model with overrides cannot: the first era draws the lit panel in the hand and the flat sprite in
 * the inventory, and the second draws the flat sprite everywhere.
 *
 * <p>A rule names one model the program draws ({@code model}, a selection's key or the base) and the model drawn instead
 * ({@code draw}) in the listed contexts ({@code only}) or in every context but the listed ones ({@code except}). There is
 * at most one rule per model. The legacy artefact has no contexts, so it ignores every rule and draws {@code model}: a rule
 * is how the item definition differs from it, never what a stack's state means.
 */
public record Context(boolean except, List<String> names, String model, String draw) {
    /** The item display contexts an item definition's {@code display_context} select can name. */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "none", "thirdperson_lefthand", "thirdperson_righthand", "firstperson_lefthand", "firstperson_righthand",
            "head", "gui", "ground", "fixed", "on_shelf"));

    public Context {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(draw, "draw");
        names = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(names)));
    }

    /** The model a stack's {@code selected} model draws in {@code context}, under this rule. */
    public String drawn(String selected, String context) {
        if (!selected.equals(model)) {
            return selected;
        }
        boolean listed = names.contains(context);
        return listed != except ? draw : model;
    }

    /** Everything wrong with the rule itself. */
    List<Diagnostic> check(String at) {
        List<Diagnostic> out = new ArrayList<>();
        if (!Ids.resource(model)) {
            out.add(new Diagnostic(Diagnostic.Kind.CONTEXT, at + ".model", "'" + model + "' is not a model reference (namespace:path)"));
        }
        if (!Ids.resource(draw)) {
            out.add(new Diagnostic(Diagnostic.Kind.CONTEXT, at + ".draw", "'" + draw + "' is not a model reference (namespace:path)"));
        }
        if (model.equals(draw)) {
            out.add(new Diagnostic(Diagnostic.Kind.CONTEXT, at, "the rule draws " + model + " instead of itself"));
        }
        if (names.isEmpty()) {
            out.add(new Diagnostic(Diagnostic.Kind.CONTEXT, at, "the rule lists no display context"));
        }
        for (String n : names) {
            if (!NAMES.contains(n)) {
                out.add(new Diagnostic(Diagnostic.Kind.CONTEXT, at, "'" + n + "' is not an item display context (" + NAMES + ")"));
            }
        }
        return out;
    }
}
