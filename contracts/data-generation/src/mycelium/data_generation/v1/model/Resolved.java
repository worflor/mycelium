package mycelium.data_generation.v1.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A candidate whose references all resolve, handed to {@link Assembler#build}: the number and hash it will publish
 * under if the build succeeds, and its {@code manifest}, every entry that survived with its content fingerprint. The
 * assembler builds from exactly the manifest's entries; anything else it read was left out.
 */
public record Resolved(long number, String hash, String interpreter, SortedMap<Ref, String> manifest, List<Diagnostic> diagnostics) {
    public Resolved {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(interpreter, "interpreter");
        manifest = Collections.unmodifiableSortedMap(new TreeMap<>(manifest));
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean has(Ref ref) {
        return manifest.containsKey(ref);
    }
}
