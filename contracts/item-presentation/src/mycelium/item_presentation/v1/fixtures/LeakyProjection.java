package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.Collections;
import java.util.List;

/** Accepts an input over a server-private component: the client would be asked to draw from state it never receives (S5). */
public final class LeakyProjection extends ReferenceSigil {
    @Override
    protected List<Diagnostic> visibility(Input input, ComponentCatalog.Component component) {
        return Collections.emptyList();
    }
}
