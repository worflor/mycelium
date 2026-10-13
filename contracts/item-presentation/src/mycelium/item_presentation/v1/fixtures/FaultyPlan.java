package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Case;
import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Domain;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Layout;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.ReferencePlan;
import mycelium.item_presentation.v1.model.ReferenceSigil;
import mycelium.item_presentation.v1.model.Selection;
import mycelium.item_presentation.v1.model.Target;
import mycelium.item_presentation.v1.model.Template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A compiler whose artefacts are right and whose run-time plan (what an adapter evaluates) gets one thing wrong. The
 * {@link Flaw} says which; one class, because every flaw is the same shape: the reference plan with one method replaced.
 */
public final class FaultyPlan extends ReferenceSigil {
    /** The mistakes, each the plan of a realistic adapter-side bug. */
    public enum Flaw {
        /** Walks the cases from the last to the first, as the legacy overrides do (S1 first match wins). */
        LAST_MATCH_WINS,
        /** A tuple no case matches draws the first selection instead of the base model (S1). */
        NO_CASE_DRAWS_FIRST_SELECTION,
        /** The selection it returns carries a code that is not in the plan's table (S1). */
        CODE_OFF_THE_TABLE,
        /** Reads a stack outside the domains as the default value and reports it in domain (S1). */
        IGNORES_DOMAIN_FLAG,
        /** A stack with no component at all is reported as outside the domains (S1). */
        ABSENT_IS_FOREIGN,
        /** Compiles the program without dropping the inputs no case reads (S1). */
        KEEPS_UNREAD_INPUTS,
        /** Reads a flag input as "the component is on the stack" rather than the flag (S2 adapter). */
        FLAG_AS_PRESENCE,
        /** Reads a field by the input's name instead of the field path it declares (S2 carrier). */
        FIELD_BY_INPUT_NAME,
        /** The plan resource it writes lacks the last selection (S2 plan resource). */
        RESOURCE_LACKS_A_SELECTION,
        /** The plan resource it writes lacks the display context rules (S8). */
        RESOURCE_LACKS_CONTEXT_RULES
    }

    private final Flaw flaw;

    public FaultyPlan(Flaw flaw) {
        this.flaw = flaw;
    }

    @Override
    public Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target) {
        Compilation result = super.compile(program, lock, catalog, target);
        if (flaw != Flaw.KEEPS_UNREAD_INPUTS || !(result instanceof Compilation.Compiled done)) {
            return result;
        }
        Map<String, Integer> codes = new TreeMap<>();
        for (Plan.Entry e : done.plan().entries()) {
            codes.put(e.key(), e.code());
        }
        Plan unpruned = new ReferencePlan(program, target.realization(), codes);
        Map<String, String> files = new TreeMap<>(done.artefacts());
        files.put(Layout.plan(program.item()), Json.write(unpruned.toTree()));
        return new Compilation.Compiled(unpruned, files);
    }

    @Override
    protected Plan newPlan(Program pruned, Realization realization, Map<String, Integer> codes) {
        Program written = pruned;
        if (flaw == Flaw.RESOURCE_LACKS_CONTEXT_RULES) {
            written = new Program(pruned.item(), pruned.base(), pruned.inputs(), pruned.cases());
        }
        return new Flawed(flaw, super.newPlan(written, realization, codes));
    }

    private static final class Flawed extends DelegatingPlan {
        private final Flaw flaw;

        Flawed(Flaw flaw, Plan inner) {
            super(inner);
            this.flaw = flaw;
        }

        private Selection pick(String model) {
            if (model.equals(base())) {
                return Selection.base(model, true);
            }
            for (Plan.Entry e : entries()) {
                if (e.key().equals(model)) {
                    return new Selection(model, model, e.code(), true);
                }
            }
            return new Selection(model, model, 1, true);
        }

        @Override
        public Selection select(Map<String, Object> tuple) {
            switch (flaw) {
                case LAST_MATCH_WINS: {
                    List<Case> cases = program().cases();
                    for (int i = cases.size() - 1; i >= 0; i--) {
                        if (cases.get(i).matches(tuple)) {
                            return pick(Template.expand(cases.get(i).model(), tuple));
                        }
                    }
                    return pick(base());
                }
                case NO_CASE_DRAWS_FIRST_SELECTION: {
                    Selection s = super.select(tuple);
                    if (s.isBase() && !entries().isEmpty()) {
                        return pick(entries().get(0).key());
                    }
                    return s;
                }
                case CODE_OFF_THE_TABLE: {
                    Selection s = super.select(tuple);
                    return s.isBase() ? s : new Selection(s.model(), s.key(), s.code() + 1000, true);
                }
                default:
                    return super.select(tuple);
            }
        }

        @Override
        public Selection select(ComponentView view) {
            switch (flaw) {
                case IGNORES_DOMAIN_FLAG:
                    return select(Evaluator.read(program(), view).values());
                case ABSENT_IS_FOREIGN:
                    for (Input input : program().readInputs()) {
                        if (!(input.domain() instanceof Domain.Present) && view.field(input.component(), input.field()).isEmpty()) {
                            return Selection.base(base(), false);
                        }
                    }
                    return super.select(view);
                case FLAG_AS_PRESENCE:
                case FIELD_BY_INPUT_NAME:
                    return readThen(view);
                case LAST_MATCH_WINS:
                case NO_CASE_DRAWS_FIRST_SELECTION:
                case CODE_OFF_THE_TABLE: {
                    Evaluator.Reading reading = Evaluator.read(program(), view);
                    return reading.inDomain() ? select(reading.values()) : Selection.base(base(), false);
                }
                default:
                    return super.select(view);
            }
        }

        /** The reference reading with one step replaced: how an input's value is taken from the stack. */
        private Selection readThen(ComponentView view) {
            Map<String, Object> values = new LinkedHashMap<>();
            boolean inDomain = true;
            for (Input input : program().readInputs()) {
                Object value;
                if (input.domain() instanceof Domain.Present) {
                    value = Boolean.valueOf(view.has(input.component()));
                } else if (flaw == Flaw.FLAG_AS_PRESENCE && input.domain() instanceof Domain.Flag) {
                    value = Boolean.valueOf(view.has(input.component()));
                } else {
                    String path = flaw == Flaw.FIELD_BY_INPUT_NAME ? input.name() : input.field();
                    Optional<Object> read = view.field(input.component(), path);
                    value = read.isPresent() ? Evaluator.normalize(read.get()) : input.fallback();
                }
                if (value == null || !input.domain().admits(value)) {
                    inDomain = false;
                    value = input.fallback();
                }
                values.put(input.name(), value);
            }
            return inDomain ? select(values) : Selection.base(base(), false);
        }

        @Override
        public Map<String, Object> toTree() {
            Map<String, Object> tree = new LinkedHashMap<>(super.toTree());
            if (flaw == Flaw.RESOURCE_LACKS_A_SELECTION) {
                List<Object> selections = new ArrayList<>((List<?>) tree.get("selections"));
                if (!selections.isEmpty()) {
                    selections.remove(selections.size() - 1);
                }
                tree.put("selections", selections);
            }
            return tree;
        }
    }
}
