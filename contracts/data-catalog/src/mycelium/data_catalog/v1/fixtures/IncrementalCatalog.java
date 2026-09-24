package mycelium.data_catalog.v1.fixtures;

import mycelium.data_catalog.v1.model.Catalog;
import mycelium.data_catalog.v1.model.Parser;
import mycelium.data_catalog.v1.model.Rejection;
import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_catalog.v1.model.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deliberately incorrect: publishes as it goes (a new snapshot after every file), so a reader mid-reload sees half
 * of the new set and none of the old.
 */
public final class IncrementalCatalog<T> implements Catalog<T> {
    private volatile Snapshot<T> current = Snapshot.empty();

    @Override
    public Snapshot<T> current() {
        return current;
    }

    @Override
    public Snapshot<T> reload(List<Source> sources, Parser<T> parser) {
        long gen = current.generation() + 1;
        Map<String, T> entries = new HashMap<>();
        List<Rejection> rejected = new ArrayList<>();
        List<Source> ordered = new ArrayList<>(sources);
        ordered.sort(java.util.Comparator.comparingInt(Source::packOrder).thenComparing(Source::id));
        current = new Snapshot<>(gen, entries, rejected); // BUG: published before anything is parsed
        for (Source s : ordered) {
            try {
                entries.put(s.id(), parser.parse(s.id(), s.text()));
            } catch (Exception e) {
                rejected.add(new Rejection(s.id(), e.getMessage()));
            }
            current = new Snapshot<>(gen, entries, rejected);
        }
        return current;
    }
}
