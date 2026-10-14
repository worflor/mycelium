package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Generation;

import java.util.Optional;

/**
 * Deliberately incorrect: every generation is number 1, so a log line or a status command cannot tell the
 * live generation from the one before it.
 */
public final class ConstantNumberGenerations<D, G> extends Pipeline<D, G> {
    public ConstantNumberGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected long number(Optional<Generation<G>> live) {
        return 1; // BUG
    }
}
