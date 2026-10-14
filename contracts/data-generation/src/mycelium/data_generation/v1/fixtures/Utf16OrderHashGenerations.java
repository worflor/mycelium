package mycelium.data_generation.v1.fixtures;

import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Part;
import mycelium.data_generation.v1.model.Ref;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Deliberately incorrect: sorts the listing with String.compareTo (UTF-16 code units), which differs from the
 * canonical code point order for characters above U+FFFF, so other tooling names the same data otherwise.
 */
public final class Utf16OrderHashGenerations<D, G> extends Pipeline<D, G> {
    public Utf16OrderHashGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected String hash(String interpreter, SortedMap<Ref, String> manifest, List<Part> listed, Map<String, ? extends Snapshot<?>> offered) {
        List<Map.Entry<Ref, String>> listing = new ArrayList<>(manifest.entrySet());
        listing.sort(Comparator.comparing((Map.Entry<Ref, String> e) -> e.getKey().kind())
                .thenComparing(e -> e.getKey().id())); // BUG: UTF-16 order
        return listingHash(interpreter, listing);
    }
}
