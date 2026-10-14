package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Diagnostic;
import mycelium.data_generation.v1.model.Generation;
import mycelium.data_generation.v1.model.Outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deliberately incorrect: on a refusal it keeps the live generation, but rewrites it to carry the refused
 * candidate's findings (so the status command shows them): the live generation is no longer the one published, and
 * no longer equals what its holders hold.
 */
public final class DisturbingGenerations<D, G> extends Pipeline<D, G> {
    public DisturbingGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected Outcome<G> kept(Optional<Generation<G>> live, List<Diagnostic> found) {
        if (live.isPresent()) {
            Generation<G> g = live.get();
            List<Diagnostic> all = new ArrayList<>(g.diagnostics());
            all.addAll(found);
            current = Optional.of(new Generation<>(g.number(), g.hash(), g.interpreter(), g.manifest(), g.value(), all)); // BUG
        }
        return new Outcome.Kept<>(current, report(found));
    }
}
