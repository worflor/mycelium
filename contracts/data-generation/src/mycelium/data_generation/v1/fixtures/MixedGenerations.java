package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Generation;
import mycelium.data_generation.v1.model.Ref;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;

/**
 * Deliberately incorrect: keeps the live name (number, hash, manifest) apart from the value, and writes the new
 * name before the new value is built, so a reader in between sees one generation's name on another's data.
 */
public final class MixedGenerations<D, G> extends Pipeline<D, G> {
    public MixedGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    private volatile Generation<?> named; // the new name, written early

    @Override
    protected void beforeBuild(long number, String hash, String interpreter, SortedMap<Ref, String> manifest) {
        named = new Generation<>(number, hash, interpreter, manifest, "pending", List.of()); // BUG: the name goes out first
    }

    @Override
    protected void publish(Generation<G> next) {
        super.publish(next);
        named = null;
    }

    @Override
    public Optional<Generation<G>> current() {
        Optional<Generation<G>> live = current;
        Generation<?> n = named;
        if (n == null || live.isEmpty()) {
            return live;
        }
        Generation<G> old = live.get();
        return Optional.of(new Generation<>(n.number(), n.hash(), n.interpreter(), n.manifest(), old.value(), old.diagnostics()));
    }
}
