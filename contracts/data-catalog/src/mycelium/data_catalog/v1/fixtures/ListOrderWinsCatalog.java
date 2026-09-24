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

/** Deliberately incorrect: whichever duplicate comes last in the offered list wins, not the later pack. */
public final class ListOrderWinsCatalog<T> implements Catalog<T> {
    private Snapshot<T> current = Snapshot.empty();

    @Override
    public Snapshot<T> current() {
        return current;
    }

    @Override
    public Snapshot<T> reload(List<Source> sources, Parser<T> parser) {
        Map<String, T> entries = new HashMap<>();
        List<Rejection> rejected = new ArrayList<>();
        for (Source s : sources) {
            try {
                entries.put(s.id(), parser.parse(s.id(), s.text()));
            } catch (Exception e) {
                rejected.add(new Rejection(s.id(), e.getMessage()));
            }
        }
        current = new Snapshot<>(current.generation() + 1, entries, rejected);
        return current;
    }
}
