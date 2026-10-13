package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Case;
import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Context;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.ReferenceSigil;
import mycelium.item_presentation.v1.model.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A compiler that tidies a program it was handed instead of refusing it: it quietly drops the part that is wrong and
 * compiles the rest. The build then succeeds on a program the author never meant (P1, S8). The {@link Mode} says what it drops.
 */
public final class LenientCompiler extends ReferenceSigil {
    /** What is dropped. */
    public enum Mode {
        /** Cases that guard an input the program never declares. */
        UNDECLARED_GUARDS,
        /** The second and later rules for one model. */
        DUPLICATE_RULES,
        /** Display context names it does not know (and a rule left with none). */
        UNKNOWN_CONTEXTS,
        /** Rules for a model the program never draws. */
        UNDRAWN_RULES
    }

    private final Mode mode;

    public LenientCompiler(Mode mode) {
        this.mode = mode;
    }

    @Override
    public Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target) {
        return super.compile(tidy(program), lock, catalog, target);
    }

    private Program tidy(Program p) {
        List<Case> cases = new ArrayList<>(p.cases());
        List<Context> rules = new ArrayList<>();
        switch (mode) {
            case UNDECLARED_GUARDS:
                cases.removeIf(c -> c.when().keySet().stream().anyMatch(name -> p.input(name) == null));
                rules.addAll(p.contexts());
                break;
            case DUPLICATE_RULES: {
                TreeSet<String> seen = new TreeSet<>();
                for (Context rule : p.contexts()) {
                    if (seen.add(rule.model())) {
                        rules.add(rule);
                    }
                }
                break;
            }
            case UNKNOWN_CONTEXTS:
                for (Context rule : p.contexts()) {
                    List<String> known = new ArrayList<>(rule.names());
                    known.retainAll(Context.NAMES);
                    if (!known.isEmpty()) {
                        rules.add(new Context(rule.except(), known, rule.model(), rule.draw()));
                    }
                }
                break;
            default: {
                Program pruned = p.pruned();
                TreeSet<String> drawn = new TreeSet<>();
                drawn.add(p.base());
                if (p.check().isEmpty() && Evaluator.product(pruned.inputs()) <= maxTuples()) {
                    for (Map<String, Object> tuple : Evaluator.tuples(pruned.inputs())) {
                        drawn.add(Evaluator.evaluate(pruned, tuple));
                    }
                }
                for (Context rule : p.contexts()) {
                    if (drawn.contains(rule.model())) {
                        rules.add(rule);
                    }
                }
            }
        }
        return new Program(p.item(), p.base(), p.inputs(), cases, rules);
    }
}
