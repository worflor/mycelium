package mycelium.data_catalog.v1.model;

import java.util.List;

/**
 * A reloadable, validated set of named data entries (data.catalog@1): rules, words, strata, anything a datapack
 * defines. The laws (SPEC.md): every valid source loads; a bad one is rejected alone; a later pack wins; the order
 * of the offered list never matters; a reload replaces everything (nothing stale survives) and is atomic (a reader
 * sees the whole old snapshot or the whole new one); a reload never throws.
 */
public interface Catalog<T> {
    Snapshot<T> current();

    Snapshot<T> reload(List<Source> sources, Parser<T> parser);
}
