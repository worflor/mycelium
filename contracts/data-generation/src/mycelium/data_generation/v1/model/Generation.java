package mycelium.data_generation.v1.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One published generation (SPEC.md §2): its number in this run, its {@code hash} (lower-case hex SHA-256, {@link
 * Hashing#generation}), the interpreter it was built for, its {@code manifest} (every published entry and its content
 * fingerprint), the assembler's value, and every finding of its assembly. Immutable: what a holder sees never changes.
 */
public record Generation<G>(long number, String hash, String interpreter, SortedMap<Ref, String> manifest, G value,
                            List<Diagnostic> diagnostics) {
    public Generation {
        if (number < 1) {
            throw new IllegalArgumentException("a generation's number starts at 1");
        }
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(interpreter, "interpreter");
        manifest = Collections.unmodifiableSortedMap(new TreeMap<>(manifest));
        Objects.requireNonNull(value, "value");
        diagnostics = List.copyOf(diagnostics);
    }
}
