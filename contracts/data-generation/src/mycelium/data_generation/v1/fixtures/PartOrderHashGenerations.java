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
 * Deliberately incorrect: hashes the entries in the order the assembler listed them (file order), so the same
 * data read in another order has another name.
 */
public final class PartOrderHashGenerations<D, G> extends Pipeline<D, G> {
    public PartOrderHashGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected String hash(String interpreter, SortedMap<Ref, String> manifest, List<Part> listed, Map<String, ? extends Snapshot<?>> offered) {
        List<Map.Entry<Ref, String>> listing = new ArrayList<>();
        for (Part p : listed) {
            if (manifest.containsKey(p.ref())) {
                listing.add(Map.entry(p.ref(), manifest.get(p.ref())));
            }
        }
        return listingHash(interpreter, listing); // BUG: listing order, not code point order
    }
}
