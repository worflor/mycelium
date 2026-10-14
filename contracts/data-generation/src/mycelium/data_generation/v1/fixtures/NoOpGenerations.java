package mycelium.data_generation.v1.fixtures;

import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Generation;
import mycelium.data_generation.v1.model.Generations;
import mycelium.data_generation.v1.model.Outcome;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Does nothing: never publishes, so it is never partial, never mixed, never dangling and never wrongly named. It is in
 * the matrix because a model contract's laws must fail a type that does nothing (CONTRACT-PACKAGE.md): P1 says a
 * consistent candidate is published.
 */
public final class NoOpGenerations<D, G> implements Generations<G> {
    public NoOpGenerations(Assembler<D, G> assembler) {
    }

    @Override
    public Optional<Generation<G>> current() {
        return Optional.empty();
    }

    @Override
    public Outcome<G> offer(Map<String, ? extends Snapshot<?>> offered) {
        return new Outcome.Kept<>(Optional.empty(), List.of());
    }
}
