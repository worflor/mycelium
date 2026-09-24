package mycelium.data_catalog.v1.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything a catalog holds after one reload: entries by id, in id order, and what was rejected. Immutable: a
 * reader that holds a snapshot keeps seeing exactly it, whatever reloads happen after.
 */
public record Snapshot<T>(long generation, Map<String, T> entries, List<Rejection> rejected) {
    public Snapshot {
        entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        rejected = List.copyOf(rejected);
    }

    public static <T> Snapshot<T> empty() {
        return new Snapshot<>(0, Map.of(), List.of());
    }
}
