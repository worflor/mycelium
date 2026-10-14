package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.ReferenceMosaic;
import mycelium.item_presentation.v1.model.Diagnostic;

import java.util.Collections;
import java.util.List;

/** Accepts a part that spans more tuples than the cap, and enumerates it (M7). */
public final class CapIgnoring extends ReferenceMosaic {
    @Override
    protected List<Diagnostic> boundDiagnostics(Mosaic mosaic) {
        return Collections.emptyList();
    }
}
