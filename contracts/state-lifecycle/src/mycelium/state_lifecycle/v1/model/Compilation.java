package mycelium.state_lifecycle.v1.model;

import java.util.Collections;
import java.util.List;

/** What compiling a declaration gives: a registry, or every diagnostic that stops it from being one. */
public sealed interface Compilation permits Compilation.Accepted, Compilation.Rejected {
    record Accepted(SlotRegistry registry) implements Compilation {
    }

    record Rejected(List<Diagnostic> diagnostics) implements Compilation {
        public Rejected {
            diagnostics = Collections.unmodifiableList(new java.util.ArrayList<>(diagnostics));
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("a rejection has a diagnostic");
            }
        }
    }
}
