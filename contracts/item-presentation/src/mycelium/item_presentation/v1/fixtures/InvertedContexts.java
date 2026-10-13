package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Context;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Broken on purpose: reads "only these contexts" as "every context but these" and the other way round, so a panel that
 * should be lit in the hand and flat in the inventory is flat in the hand and lit in the inventory. Nothing in the plan
 * or the legacy model shows it; only an item definition asked in each display context does (law S8).
 */
public final class InvertedContexts extends ReferenceSigil {
    @Override
    protected Map<String, Object> drawn(String ref, List<Context> contexts) {
        List<Context> flipped = new ArrayList<>();
        for (Context c : contexts) {
            flipped.add(new Context(!c.except(), c.names(), c.model(), c.draw()));
        }
        return super.drawn(ref, flipped);
    }
}
