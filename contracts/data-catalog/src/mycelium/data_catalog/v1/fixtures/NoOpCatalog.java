package mycelium.data_catalog.v1.fixtures;

import mycelium.data_catalog.v1.model.Catalog;
import mycelium.data_catalog.v1.model.Parser;
import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_catalog.v1.model.Source;

import java.util.List;

/**
 * Does nothing: every reload answers the empty snapshot. It never throws, never goes stale and is trivially atomic,
 * which is why it must be in the matrix: T1 says every valid source loads, so a catalog that loads nothing fails.
 */
public final class NoOpCatalog<T> implements Catalog<T> {
    @Override
    public Snapshot<T> current() {
        return Snapshot.empty();
    }

    @Override
    public Snapshot<T> reload(List<Source> sources, Parser<T> parser) {
        return Snapshot.empty();
    }
}
