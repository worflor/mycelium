package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.ArrayList;
import java.util.List;

/** A compiler whose projection check (S5) lets one kind of refusal through: it accepts what the check would have refused by that kind. */
public final class FaultyProjection extends ReferenceSigil {
    private final Diagnostic.Kind lenient;

    /** @param lenient the refusal kind it drops: {@code CATALOG} (an unknown component or field) or {@code TYPE} (a domain of another kind) */
    public FaultyProjection(Diagnostic.Kind lenient) {
        this.lenient = lenient;
    }

    @Override
    protected List<Diagnostic> projectionDiagnostics(Program program, ComponentCatalog catalog) {
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic d : super.projectionDiagnostics(program, catalog)) {
            if (d.kind() != lenient) {
                kept.add(d);
            }
        }
        return kept;
    }
}
