package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Diagnostic;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberately incorrect: reports findings up to the first error and stops, so a pack author fixes one problem
 * per reload.
 */
public final class FirstFindingGenerations<D, G> extends Pipeline<D, G> {
    public FirstFindingGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected List<Diagnostic> report(List<Diagnostic> found) {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : super.report(found)) {
            out.add(d);
            if (d.isError()) {
                break; // BUG
            }
        }
        return out;
    }
}
