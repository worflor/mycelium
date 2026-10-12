package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Layout;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.ReferencePlan;
import mycelium.item_presentation.v1.model.Selection;
import mycelium.item_presentation.v1.model.Selector;
import mycelium.item_presentation.v1.model.SigilCompiler;
import mycelium.item_presentation.v1.model.Target;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Does nothing: every item draws its base model, whatever its state. Nothing it emits is malformed, and nothing it emits is the program. */
public final class NoOpCompiler implements SigilCompiler {
    @Override
    public int maxTuples() {
        return Evaluator.DEFAULT_MAX_TUPLES;
    }

    @Override
    public Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target) {
        if (target.realization() == Realization.NONE) {
            return new Compilation.Rejected(java.util.Collections.singletonList(new mycelium.item_presentation.v1.model.Diagnostic(
                    mycelium.item_presentation.v1.model.Diagnostic.Kind.NO_REALIZATION, program.item(), "Minecraft " + target.minecraft() + " has nothing to draw with")));
        }
        Plan plan = new BaseOnly(program, target.realization());
        Map<String, String> files = new TreeMap<>();
        Map<String, Object> model = new LinkedHashMap<>();
        if (target.realization() == Realization.ITEM_DEFINITION) {
            Map<String, Object> plain = new LinkedHashMap<>();
            plain.put("type", "minecraft:model");
            plain.put("model", program.base());
            model.put("model", plain);
            files.put(Layout.definition(program.item()), Json.write(model));
        } else {
            model.put("parent", program.base());
            model.put("overrides", new java.util.ArrayList<Object>());
            files.put(Layout.model(program.item()), Json.write(model));
        }
        files.put(Layout.plan(program.item()), Json.write(plan.toTree()));
        return new Compilation.Compiled(plan, files);
    }

    private record BaseOnly(Program program, Realization realization) implements Plan {
        @Override
        public String item() {
            return program.item();
        }

        @Override
        public String base() {
            return program.base();
        }

        @Override
        public List<Entry> entries() {
            return Collections.emptyList();
        }

        @Override
        public Selection select(ComponentView view) {
            return Selection.base(program.base(), true);
        }

        @Override
        public Selection select(Map<String, Object> tuple) {
            return Selection.base(program.base(), true);
        }

        @Override
        public Selector selectorOf(Selection selection) {
            return new Selector.None();
        }

        @Override
        public Map<String, Object> toTree() {
            return new ReferencePlan(program.pruned(), realization, Collections.<String, Integer>emptyMap()).toTree();
        }
    }
}
