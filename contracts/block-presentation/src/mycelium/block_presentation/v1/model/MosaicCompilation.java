package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.Diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The outcome of compiling a mosaic: what it produced, or every reason it was refused. */
public sealed interface MosaicCompilation {
    /**
     * @param plan      what the platform adapter evaluates
     * @param artefacts every file to write, by path relative to a resources root, as text: the plan resource ({@link Layout#plan})
     */
    record Compiled(MosaicPlan plan, Map<String, String> artefacts) implements MosaicCompilation {
        public Compiled {
            artefacts = Collections.unmodifiableMap(new TreeMap<>(artefacts));
        }
    }

    record Rejected(List<Diagnostic> diagnostics) implements MosaicCompilation {
        public Rejected {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("a rejection gives at least one reason");
            }
            diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }
}
