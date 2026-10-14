package mycelium.data_generation.v1.fixtures;

import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Part;
import mycelium.data_generation.v1.model.Ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Deliberately incorrect: hashes kind by kind in the offered map's iteration order, so the same data offered
 * through another map (a HashMap, or kinds registered in another order) has another name.
 */
public final class MapOrderHashGenerations<D, G> extends Pipeline<D, G> {
    public MapOrderHashGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected String hash(String interpreter, SortedMap<Ref, String> manifest, List<Part> listed, Map<String, ? extends Snapshot<?>> offered) {
        List<Map.Entry<Ref, String>> listing = new ArrayList<>();
        for (String kind : offered.keySet()) { // BUG: the map's order
            for (Map.Entry<Ref, String> e : manifest.entrySet()) {
                if (e.getKey().kind().equals(kind)) {
                    listing.add(e);
                }
            }
        }
        return listingHash(interpreter, listing);
    }
}
