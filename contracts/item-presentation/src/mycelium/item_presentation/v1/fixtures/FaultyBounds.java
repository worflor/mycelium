package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.ReferenceSigil;
import mycelium.item_presentation.v1.model.Target;

/** A compiler that gets the cap on the tuples (S7) wrong. */
public final class FaultyBounds extends ReferenceSigil {
    /** The mistakes. */
    public enum Flaw {
        /** Refuses a product of exactly the cap it states: the comparison is {@code >=}. */
        CAP_IS_EXCLUSIVE,
        /** Enumerates the tuples first and compares the count afterwards, so a huge product never reaches the comparison. */
        COUNTS_AFTER_ENUMERATING
    }

    private final Flaw flaw;
    private final int stated;

    public FaultyBounds(Flaw flaw) {
        super(flaw == Flaw.CAP_IS_EXCLUSIVE ? Evaluator.DEFAULT_MAX_TUPLES - 1 : Evaluator.DEFAULT_MAX_TUPLES);
        this.flaw = flaw;
        this.stated = Evaluator.DEFAULT_MAX_TUPLES;
    }

    /** The cap it says it keeps. */
    @Override
    public int maxTuples() {
        return stated;
    }

    @Override
    public Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target) {
        if (flaw == Flaw.COUNTS_AFTER_ENUMERATING) {
            Evaluator.tuples(program.pruned().inputs());
        }
        return super.compile(program, lock, catalog, target);
    }
}
