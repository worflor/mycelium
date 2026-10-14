package mycelium.data_generation.v1.model;

import java.util.List;
import java.util.Objects;

/**
 * What an assembler's build makes of a resolved candidate: the generation's value, or a refusal of the candidate as a
 * whole. A {@link Built} that carries an ERROR finding is a refusal too.
 */
public sealed interface Assembly<G> {
    List<Diagnostic> diagnostics();

    record Built<G>(G value, List<Diagnostic> diagnostics) implements Assembly<G> {
        public Built {
            Objects.requireNonNull(value, "value");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record Refused<G>(List<Diagnostic> diagnostics) implements Assembly<G> {
        public Refused {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
