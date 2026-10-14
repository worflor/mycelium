package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;

/**
 * Deliberately incorrect: drops an optional entry whose reference dangles, but in one pass against the entries
 * as read, so whatever referred to the dropped entry stays, pointing at nothing.
 */
public final class ShallowDropGenerations<D, G> extends Pipeline<D, G> {
    public ShallowDropGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected boolean transitive() {
        return false; // BUG: no fixed point
    }
}
