package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.ReferenceMosaic;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Input;

import java.util.Collections;
import java.util.List;

/** Accepts an input over a component the client is never sent (M5). */
public final class LeakyProjection extends ReferenceMosaic {
    @Override
    protected List<Diagnostic> visibility(Input input, ComponentCatalog.Component component) {
        return Collections.emptyList();
    }
}
