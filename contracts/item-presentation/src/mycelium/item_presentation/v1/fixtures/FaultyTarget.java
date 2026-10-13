package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Layout;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.ReferenceSigil;
import mycelium.item_presentation.v1.model.Target;

import java.util.Map;
import java.util.TreeMap;

/** A compiler that gets what it does for a version wrong (R1, R2): the era table's answer is mishandled, not the compile itself. */
public final class FaultyTarget extends ReferenceSigil {
    /** The mistakes. */
    public enum Flaw {
        /** A version with no presentation mechanism is compiled as if it had an item definition. */
        NO_MECHANISM_COMPILES_ANYWAY,
        /** The legacy item model is written without the check that no selection is the item's own model, which would be its own parent. */
        LEGACY_SKIPS_CLASH_CHECK
    }

    private final Flaw flaw;

    public FaultyTarget(Flaw flaw) {
        this.flaw = flaw;
    }

    @Override
    public Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target) {
        if (flaw == Flaw.NO_MECHANISM_COMPILES_ANYWAY) {
            return super.compile(program, lock, catalog,
                    target.realization() == Realization.NONE ? new Target(target.minecraft(), Realization.ITEM_DEFINITION) : target);
        }
        if (target.realization() != Realization.MODEL_OVERRIDES) {
            return super.compile(program, lock, catalog, target);
        }
        // the checks of the modern compile, then the legacy file built from its codes
        Compilation modern = super.compile(program, lock, catalog, new Target(target.minecraft(), Realization.ITEM_DEFINITION));
        if (!(modern instanceof Compilation.Compiled done)) {
            return modern;
        }
        Map<String, Integer> codes = new TreeMap<>();
        for (Plan.Entry e : done.plan().entries()) {
            codes.put(e.key(), e.code());
        }
        Program pruned = program.pruned();
        Plan plan = newPlan(pruned, Realization.MODEL_OVERRIDES, codes);
        Map<String, String> files = new TreeMap<>();
        files.put(Layout.model(program.item()), render(modelOverrides(program.base(), codes, lock.entries(program.item()))));
        files.put(Layout.plan(program.item()), render(plan.toTree()));
        return new Compilation.Compiled(plan, files);
    }
}
