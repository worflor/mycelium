package mycelium.data_catalog.v1.fixtures;

import mycelium.data_catalog.v1.model.Catalog;
import mycelium.data_catalog.v1.model.Parser;
import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_catalog.v1.model.Source;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deliberately incorrect: the first bad file aborts the reload (and the exception escapes). */
public final class AbortOnFirstBadCatalog<T> implements Catalog<T> {
    private Snapshot<T> current = Snapshot.empty();

    @Override
    public Snapshot<T> current() {
        return current;
    }

    @Override
    public Snapshot<T> reload(List<Source> sources, Parser<T> parser) {
        Map<String, T> entries = new HashMap<>();
        for (Source s : sources) {
            try {
                entries.put(s.id(), parser.parse(s.id(), s.text()));
            } catch (Exception e) {
                throw new IllegalStateException("bad file " + s.id(), e);
            }
        }
        current = new Snapshot<>(current.generation() + 1, entries, List.of());
        return current;
    }
}
