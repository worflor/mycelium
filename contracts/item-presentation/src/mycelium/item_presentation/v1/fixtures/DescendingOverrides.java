package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.ArrayList;
import java.util.List;

/** Writes the legacy overrides highest code first: under "the last match wins" every stack then draws the lowest match (S4, S2). */
public final class DescendingOverrides extends ReferenceSigil {
    @Override
    protected List<Plan.Entry> overrideOrder(List<Plan.Entry> rows) {
        List<Plan.Entry> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Integer.compare(b.code(), a.code()));
        return sorted;
    }
}
