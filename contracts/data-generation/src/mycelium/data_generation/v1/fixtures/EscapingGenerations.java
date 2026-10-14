package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;

/**
 * Deliberately incorrect: what the assembler throws escapes the offer, so one bad reload takes the reload
 * (and whatever called it) down with it.
 */
public final class EscapingGenerations<D, G> extends Pipeline<D, G> {
    public EscapingGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected boolean contain() {
        return false; // BUG
    }
}
