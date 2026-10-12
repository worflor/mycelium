package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.ReferenceSigil;
import mycelium.item_presentation.v1.model.Selection;
import mycelium.item_presentation.v1.model.Selector;

import java.util.List;
import java.util.Map;

/** Compiles the right artefacts, then tells the adapter to write code + 1 onto the stack: every stack but the last draws its neighbour (S2). */
public final class OffByOneLegacy extends ReferenceSigil {
    @Override
    protected Plan newPlan(Program pruned, Realization realization, Map<String, Integer> codes) {
        return new Shifted(super.newPlan(pruned, realization, codes));
    }

    private record Shifted(Plan inner) implements Plan {
        @Override
        public String item() {
            return inner.item();
        }

        @Override
        public Realization realization() {
            return inner.realization();
        }

        @Override
        public String base() {
            return inner.base();
        }

        @Override
        public Program program() {
            return inner.program();
        }

        @Override
        public List<Entry> entries() {
            return inner.entries();
        }

        @Override
        public Selection select(ComponentView view) {
            return inner.select(view);
        }

        @Override
        public Selection select(Map<String, Object> tuple) {
            return inner.select(tuple);
        }

        @Override
        public Selector selectorOf(Selection selection) {
            Selector s = inner.selectorOf(selection);
            return s instanceof Selector.Code c ? new Selector.Code(c.code() + 1) : s;
        }

        @Override
        public Map<String, Object> toTree() {
            return inner.toTree();
        }
    }
}
