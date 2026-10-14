package mycelium.data_generation.v1.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** What an offer did: published a new generation, or kept the live one (or none) and said why. */
public sealed interface Outcome<G> {
    /** The live generation after the offer. */
    Optional<Generation<G>> live();

    record Published<G>(Generation<G> generation) implements Outcome<G> {
        public Published {
            Objects.requireNonNull(generation, "generation");
        }

        @Override
        public Optional<Generation<G>> live() {
            return Optional.of(generation);
        }
    }

    /** The candidate was refused; {@code current} is the generation that keeps serving, untouched. */
    record Kept<G>(Optional<Generation<G>> current, List<Diagnostic> diagnostics) implements Outcome<G> {
        public Kept {
            Objects.requireNonNull(current, "current");
            diagnostics = List.copyOf(diagnostics);
        }

        @Override
        public Optional<Generation<G>> live() {
            return current;
        }
    }
}
