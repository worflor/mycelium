package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The outcome of compiling a program: what it produced, or every reason it was refused. */
public sealed interface Compilation {
    /**
     * @param plan      what the platform adapter evaluates
     * @param artefacts every file to write, by path relative to a resources root, as text: the era's artefact and the
     *                  plan resource ({@link Layout#plan})
     */
    record Compiled(Plan plan, Map<String, String> artefacts) implements Compilation {
        public Compiled {
            artefacts = Collections.unmodifiableMap(new TreeMap<>(artefacts));
        }
    }

    record Rejected(List<Diagnostic> diagnostics) implements Compilation {
        public Rejected {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("a rejection gives at least one reason");
            }
            diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }
}
