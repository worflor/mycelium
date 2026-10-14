package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;

/**
 * Deliberately incorrect: never resolves references, so a binding to a structure that is not there, or a
 * ruleset naming a missing table, is published.
 */
public final class DanglingTolerantGenerations<D, G> extends Pipeline<D, G> {
    public DanglingTolerantGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected boolean checkReferences() {
        return false; // BUG
    }
}
