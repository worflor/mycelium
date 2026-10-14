package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;

/**
 * Deliberately incorrect: a refused candidate is published anyway, as whatever survived resolution. A ruleset
 * that fails its safety check, or a required entry whose reference dangles, goes live minus the broken part.
 */
public final class FailOpenGenerations<D, G> extends Pipeline<D, G> {
    public FailOpenGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected boolean honourRefusal() {
        return false; // BUG: a refusal is noted and then ignored
    }
}
