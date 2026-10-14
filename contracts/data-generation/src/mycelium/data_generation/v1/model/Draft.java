package mycelium.data_generation.v1.model;

import java.util.List;

/**
 * What an assembler read from one offer (SPEC.md §2): its own parsed form ({@code value}, handed back to
 * {@link Assembler#build}), a {@link Part} for every entry it read, and every finding. An entry an ERROR finding names
 * need not have a part; every other offered entry must have exactly one.
 */
public record Draft<D>(D value, List<Part> parts, List<Diagnostic> diagnostics) {
    public Draft {
        parts = List.copyOf(parts);
        diagnostics = List.copyOf(diagnostics);
    }
}
