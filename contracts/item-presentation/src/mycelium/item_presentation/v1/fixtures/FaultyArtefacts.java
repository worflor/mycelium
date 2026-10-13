package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Context;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Layout;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.ReferenceSigil;
import mycelium.item_presentation.v1.model.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A compiler whose plan is right and whose emitted file gets one thing wrong. The {@link Flaw} says which; one class,
 * because every flaw is the reference emitter with one step replaced.
 */
public final class FaultyArtefacts extends ReferenceSigil {
    /** The mistakes, each a realistic bug in a build step that writes the era's file. */
    public enum Flaw {
        /** The legacy overrides forget the codes the program no longer produces, so a stack still carrying one draws its neighbour (S2). */
        RETIRED_ROWS_DROPPED,
        /** The item definition has a case for every selection but the last (S2). */
        LAST_CASE_DROPPED,
        /** The item definition lists its cases from the highest key to the lowest (S6). */
        CASES_IN_REVERSE,
        /** The files are written with Windows line endings (S6). */
        CRLF_LINE_ENDINGS,
        /** The text is case-normalised with the default locale's rules, which a Turkish default turns into non-ASCII letters (S6). */
        LOCALE_CASE_FOLDING,
        /** Each file carries the number of files this compiler has written, as a build stamp (S6). */
        BUILD_STAMP,
        /** A program with nothing but the base model gets no era file at all (S2). */
        EMPTY_PROGRAM_WRITES_NOTHING,
        /** The legacy item model draws a display context rule's other model, which only an item definition can ask for (S8). */
        LEGACY_APPLIES_CONTEXTS
    }

    private final Flaw flaw;
    private long written;

    public FaultyArtefacts(Flaw flaw) {
        this.flaw = flaw;
    }

    @Override
    public Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target) {
        Compilation result = super.compile(program, lock, catalog, target);
        if (!(result instanceof Compilation.Compiled done)) {
            return result;
        }
        Map<String, String> files = new TreeMap<>(done.artefacts());
        String era = target.realization() == Realization.ITEM_DEFINITION ? Layout.definition(program.item()) : Layout.model(program.item());
        if (flaw == Flaw.EMPTY_PROGRAM_WRITES_NOTHING && done.plan().entries().isEmpty()) {
            files.remove(era);
        } else if (flaw == Flaw.LEGACY_APPLIES_CONTEXTS && target.realization() == Realization.MODEL_OVERRIDES && !program.contexts().isEmpty()) {
            files.put(era, Json.write(redrawn(Json.parse(files.get(era)), program)));
        } else {
            return result;
        }
        return new Compilation.Compiled(done.plan(), files);
    }

    /** The item model with every model it draws replaced by what the program's rule for it draws elsewhere. */
    private static Object redrawn(Object model, Program program) {
        Map<?, ?> root = (Map<?, ?>) model;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parent", swap((String) root.get("parent"), program));
        List<Object> rows = new ArrayList<>();
        for (Object o : (List<?>) root.get("overrides")) {
            Map<?, ?> row = (Map<?, ?>) o;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("model", swap((String) row.get("model"), program));
            one.put("predicate", row.get("predicate"));
            rows.add(one);
        }
        out.put("overrides", rows);
        return out;
    }

    private static String swap(String model, Program program) {
        Context rule = program.contextFor(model);
        return rule == null ? model : rule.draw();
    }

    @Override
    protected Map<String, Object> modelOverrides(String base, Map<String, Integer> codes, List<Lock.Entry> locked) {
        Map<String, Object> root = super.modelOverrides(base, codes, flaw == Flaw.RETIRED_ROWS_DROPPED ? Collections.<Lock.Entry>emptyList() : locked);
        if (flaw == Flaw.BUILD_STAMP) {
            root.put("build", Long.valueOf(++written));
        }
        return root;
    }

    @Override
    protected Map<String, Object> itemDefinition(String base, List<String> keys, List<Context> contexts) {
        List<String> shown = new ArrayList<>(keys);
        if (flaw == Flaw.LAST_CASE_DROPPED && !shown.isEmpty()) {
            shown.remove(shown.size() - 1);
        } else if (flaw == Flaw.CASES_IN_REVERSE) {
            Collections.reverse(shown);
        }
        Map<String, Object> root = super.itemDefinition(base, shown, contexts);
        if (flaw == Flaw.BUILD_STAMP) {
            root.put("build", Long.valueOf(++written));
        }
        return root;
    }

    @Override
    protected String render(Object tree) {
        String text = super.render(tree);
        switch (flaw) {
            case CRLF_LINE_ENDINGS:
                return text.replace("\n", "\r\n");
            case LOCALE_CASE_FOLDING:
                return text.toUpperCase().toLowerCase(Locale.ROOT);
            default:
                return text;
        }
    }
}
