package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.Selection;
import mycelium.item_presentation.v1.model.Selector;

import java.util.List;
import java.util.Map;

/** A plan that hands every call to another; a broken plan overrides the one call it gets wrong. */
abstract class DelegatingPlan implements Plan {
    private final Plan inner;

    DelegatingPlan(Plan inner) {
        this.inner = inner;
    }

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
        return inner.selectorOf(selection);
    }

    @Override
    public Map<String, Object> toTree() {
        return inner.toTree();
    }
}
