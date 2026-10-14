package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;

/**
 * Deliberately incorrect: takes the assembler's parts as the generation without accounting for the offer. An
 * entry the assembler forgot vanishes without a word, and one it invented is published.
 */
public final class TrustingGenerations<D, G> extends Pipeline<D, G> {
    public TrustingGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected boolean account() {
        return false; // BUG
    }
}
