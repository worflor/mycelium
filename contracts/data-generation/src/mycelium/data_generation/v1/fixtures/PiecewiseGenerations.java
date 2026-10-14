package mycelium.data_generation.v1.fixtures;

import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Kind;
import mycelium.data_generation.v1.model.Outcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deliberately incorrect: publishes as each catalog arrives (the first kind alone, then the first two, and so on),
 * so for a moment the live generation is new rules over old structures, or new bindings over no structures at all.
 */
public final class PiecewiseGenerations<D, G> extends Pipeline<D, G> {
    public PiecewiseGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    public synchronized Outcome<G> offer(Map<String, ? extends Snapshot<?>> offered) {
        List<Kind> kinds = assembler.kinds();
        Outcome<G> last = null;
        for (int i = 1; i <= kinds.size(); i++) { // BUG: one publication per catalog
            Map<String, Snapshot<?>> partial = new LinkedHashMap<>();
            for (int j = 0; j < kinds.size(); j++) {
                String id = kinds.get(j).id();
                partial.put(id, j < i ? offered.get(id) : Snapshot.empty());
            }
            last = super.offer(partial);
        }
        return last;
    }
}
